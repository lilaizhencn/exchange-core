package exchange.core2.core.simulation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Serial publication lanes using the same power-of-two symbol partitioning as
 * the matching-engine routers.
 *
 * <p>Tasks in one partition are invoked in FIFO order. Their asynchronous
 * results may complete later, but their commands have already been published
 * in partition order.</p>
 */
public final class SymbolPartitionDispatcher implements AutoCloseable {

    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final int partitionMask;
    private final List<ExecutorService> lanes;
    private final List<AtomicLong> partitionSequences;
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public SymbolPartitionDispatcher(final int partitionCount) {
        if (partitionCount <= 0 || Integer.bitCount(partitionCount) != 1) {
            throw new IllegalArgumentException("partitionCount must be a positive power of two");
        }

        partitionMask = partitionCount - 1;
        lanes = new ArrayList<>(partitionCount);
        partitionSequences = new ArrayList<>(partitionCount);
        for (int partition = 0; partition < partitionCount; partition++) {
            final int partitionId = partition;
            lanes.add(Executors.newSingleThreadExecutor(
                    runnable -> Thread.ofPlatform()
                            .name("symbol-partition-" + partitionId)
                            .daemon()
                            .unstarted(runnable)));
            partitionSequences.add(new AtomicLong());
        }
    }

    public int partitionCount() {
        return lanes.size();
    }

    public int partitionFor(final int symbol) {
        return symbol & partitionMask;
    }

    public <T> CompletableFuture<PartitionResult<T>> submit(
            final int symbol,
            final Supplier<? extends CompletableFuture<T>> task) {
        Objects.requireNonNull(task, "task");
        if (!accepting.get()) {
            throw new IllegalStateException("symbol partition dispatcher is closed");
        }

        final int partition = partitionFor(symbol);
        final CompletableFuture<PartitionResult<T>> result = new CompletableFuture<>();
        lanes.get(partition).execute(() -> {
            final long sequence = partitionSequences.get(partition).incrementAndGet();
            try {
                final CompletableFuture<T> taskResult =
                        Objects.requireNonNull(task.get(), "partition task result");
                taskResult.whenComplete((value, error) -> {
                    if (error == null) {
                        result.complete(new PartitionResult<>(partition, sequence, value));
                    } else {
                        result.completeExceptionally(error);
                    }
                });
            } catch (final RuntimeException error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    /**
     * Completes after every task already queued in every partition has invoked
     * its publisher.
     */
    public CompletableFuture<Void> publicationBarrier() {
        final CompletableFuture<?>[] barriers = new CompletableFuture<?>[lanes.size()];
        for (int partition = 0; partition < lanes.size(); partition++) {
            final CompletableFuture<Void> barrier = new CompletableFuture<>();
            barriers[partition] = barrier;
            lanes.get(partition).execute(() -> barrier.complete(null));
        }
        return CompletableFuture.allOf(barriers);
    }

    @Override
    public void close() {
        if (!accepting.compareAndSet(true, false)) {
            return;
        }

        lanes.forEach(ExecutorService::shutdown);
        for (final ExecutorService lane : lanes) {
            try {
                if (!lane.awaitTermination(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    lane.shutdownNow();
                }
            } catch (final InterruptedException interrupted) {
                lane.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public record PartitionResult<T>(
            int partition,
            long partitionSequence,
            T value) {
    }
}
