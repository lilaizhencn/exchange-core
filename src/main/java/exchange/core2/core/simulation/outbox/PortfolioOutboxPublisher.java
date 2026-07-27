package exchange.core2.core.simulation.outbox;

import exchange.core2.core.simulation.http.EmporiaHttpException;
import exchange.core2.core.simulation.http.HttpEmporiaPortfolioGateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lease-based asynchronous publisher for durable portfolio events.
 */
public final class PortfolioOutboxPublisher
        implements AutoCloseable {

    private static final int MAX_ERROR_LENGTH = 2_000;

    private final PortfolioOutboxStore store;
    private final HttpEmporiaPortfolioGateway httpGateway;
    private final PortfolioOutboxConfiguration configuration;
    private final Clock clock;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public PortfolioOutboxPublisher(
            final PortfolioOutboxStore store,
            final HttpEmporiaPortfolioGateway httpGateway,
            final PortfolioOutboxConfiguration configuration) {
        this(
                store,
                httpGateway,
                configuration,
                Clock.systemUTC(),
                new ScheduledThreadPoolExecutor(
                        2,
                        daemonThreadFactory(
                                configuration.workerId())));
    }

    PortfolioOutboxPublisher(
            final PortfolioOutboxStore store,
            final HttpEmporiaPortfolioGateway httpGateway,
            final PortfolioOutboxConfiguration configuration,
            final Clock clock,
            final ScheduledExecutorService executor) {
        this.store = Objects.requireNonNull(store, "store");
        this.httpGateway =
                Objects.requireNonNull(httpGateway, "httpGateway");
        this.configuration =
                Objects.requireNonNull(configuration, "configuration");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public void start() {
        if (closed.get()) {
            throw new IllegalStateException(
                    "portfolio outbox publisher is closed");
        }
        if (started.compareAndSet(false, true)) {
            schedule(Duration.ZERO);
        }
    }

    /**
     * Claims and attempts one batch. This method is also useful for controlled
     * startup drains and integration tests.
     */
    public CompletableFuture<Integer> drainOnce() {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "portfolio outbox publisher is closed"));
        }

        return CompletableFuture.supplyAsync(
                        () -> store.claim(
                                configuration.workerId(),
                                configuration.batchSize(),
                                clock.instant(),
                                configuration.leaseDuration()),
                        executor)
                .thenCompose(records -> deliver(records)
                        .thenApply(ignored -> records.size()));
    }

    private CompletableFuture<Void> deliver(
            final List<PortfolioOutboxRecord> records) {
        final CompletableFuture<?>[] deliveries = records.stream()
                .map(this::deliver)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(deliveries);
    }

    private CompletableFuture<Void> deliver(
            final PortfolioOutboxRecord record) {
        return httpGateway.publishEncoded(record.event())
                .handle((ignored, error) -> error)
                .thenAcceptAsync(
                        error -> completeDelivery(record, error),
                        executor);
    }

    private void completeDelivery(
            final PortfolioOutboxRecord record,
            final Throwable error) {
        final Instant now = clock.instant();
        if (error == null) {
            store.markPublished(
                    record.event().eventId(),
                    configuration.workerId(),
                    now);
            return;
        }

        final Throwable cause = unwrap(error);
        final String detail = limitedError(cause);
        if (cause instanceof EmporiaHttpException httpFailure
                && !httpFailure.retryable()) {
            store.markDead(
                    record.event().eventId(),
                    configuration.workerId(),
                    detail);
            return;
        }

        store.markRetry(
                record.event().eventId(),
                configuration.workerId(),
                now.plus(retryDelay(record.attemptCount())),
                detail);
    }

    private Duration retryDelay(final int attemptCount) {
        final long initialMillis =
                configuration.initialRetryDelay().toMillis();
        final long maximumMillis =
                configuration.maximumRetryDelay().toMillis();
        final int shift = Math.min(
                Math.max(0, attemptCount - 1),
                30);
        final long exponential =
                initialMillis > (Long.MAX_VALUE >> shift)
                        ? maximumMillis
                        : initialMillis << shift;
        final long capped = Math.min(exponential, maximumMillis);
        final long floor = Math.max(1L, capped / 2L);
        final long jittered = capped <= floor
                ? capped
                : ThreadLocalRandom.current()
                        .nextLong(floor, capped + 1L);
        return Duration.ofMillis(jittered);
    }

    private void schedule(final Duration delay) {
        if (closed.get()) {
            return;
        }
        try {
            executor.schedule(
                    () -> drainOnce().whenComplete(
                            (count, error) -> schedule(
                                    error == null
                                            && count
                                            >= configuration.batchSize()
                                            ? Duration.ofMillis(1)
                                            : configuration.pollInterval())),
                    delay.toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (final RejectedExecutionException error) {
            if (!closed.get()) {
                throw error;
            }
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }

    private static ThreadFactory daemonThreadFactory(
            final String workerId) {
        return task -> {
            final Thread thread = new Thread(
                    task,
                    "emporia-portfolio-outbox-" + workerId);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String limitedError(final Throwable error) {
        final String message = error.getClass().getSimpleName()
                + ": "
                + Objects.toString(error.getMessage(), "");
        return message.length() <= MAX_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_LENGTH);
    }

    private static Throwable unwrap(final Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
