package exchange.core2.core.simulation.outbox;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import exchange.core2.core.simulation.EmporiaPortfolioSnapshot;
import exchange.core2.core.simulation.http.EmporiaHttpGatewayConfiguration;
import exchange.core2.core.simulation.http.EmporiaPortfolioHttpEvent;
import exchange.core2.core.simulation.http.HttpEmporiaPortfolioGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Timeout(10)
class PortfolioOutboxPublisherTest {

    private HttpServer server;
    private HttpEmporiaPortfolioGateway httpGateway;
    private ExecutorService serverExecutor;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0);
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        server.start();
        httpGateway = new HttpEmporiaPortfolioGateway(
                EmporiaHttpGatewayConfiguration.create(
                        URI.create(
                                "http://127.0.0.1:"
                                        + server.getAddress().getPort()),
                        "exchange-1"));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void retriesTransientFailureAndPublishesAfterRestartDrain() {
        final AtomicInteger requests = new AtomicInteger();
        server.createContext(
                "/internal/v1/portfolio-snapshots/13/101",
                exchange -> respond(
                        exchange,
                        requests.incrementAndGet() == 1 ? 503 : 204));
        final MutableClock clock =
                new MutableClock(
                        Instant.parse("2026-07-27T08:00:00Z"));
        final RecordingStore store =
                new RecordingStore(event());

        try (PortfolioOutboxPublisher publisher =
                     publisher(store, clock)) {
            assertEquals(1, publisher.drainOnce().join());
            assertEquals(State.RETRY, store.state);

            clock.advance(Duration.ofMillis(1));
            assertEquals(1, publisher.drainOnce().join());
            assertEquals(State.PUBLISHED, store.state);
            assertEquals(2, requests.get());
        }
    }

    @Test
    void movesPermanentHttpFailureToDeadState() {
        server.createContext(
                "/internal/v1/portfolio-snapshots/13/101",
                exchange -> respond(exchange, 400));
        final RecordingStore store =
                new RecordingStore(event());

        try (PortfolioOutboxPublisher publisher = publisher(
                store,
                new MutableClock(
                        Instant.parse("2026-07-27T08:00:00Z")))) {
            assertEquals(1, publisher.drainOnce().join());
            assertEquals(State.DEAD, store.state);
        }
    }

    private PortfolioOutboxPublisher publisher(
            final RecordingStore store,
            final Clock clock) {
        final PortfolioOutboxConfiguration configuration =
                new PortfolioOutboxConfiguration(
                        "test-worker",
                        10,
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(1),
                        Duration.ofMillis(1));
        return new PortfolioOutboxPublisher(
                store,
                httpGateway,
                configuration,
                clock,
                new ScheduledThreadPoolExecutor(2));
    }

    private EmporiaPortfolioHttpEvent event() {
        return httpGateway.encode(
                new EmporiaPortfolioSnapshot(
                        13,
                        101,
                        Map.of(840, 500L)));
    }

    private static void respond(
            final HttpExchange exchange,
            final int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private enum State {
        PENDING,
        IN_FLIGHT,
        RETRY,
        PUBLISHED,
        DEAD
    }

    private static final class RecordingStore
            implements PortfolioOutboxStore {

        private final EmporiaPortfolioHttpEvent event;
        private State state = State.PENDING;
        private int attempts;
        private Instant nextAttemptAt = Instant.MIN;

        private RecordingStore(
                final EmporiaPortfolioHttpEvent event) {
            this.event = event;
        }

        @Override
        public void enqueue(
                final EmporiaPortfolioHttpEvent ignored) {
            throw new UnsupportedOperationException();
        }

        @Override
        public synchronized List<PortfolioOutboxRecord> claim(
                final String workerId,
                final int batchSize,
                final Instant now,
                final Duration leaseDuration) {
            if ((state == State.PENDING || state == State.RETRY)
                    && !nextAttemptAt.isAfter(now)) {
                state = State.IN_FLIGHT;
                attempts++;
                return List.of(new PortfolioOutboxRecord(
                        1,
                        event,
                        attempts));
            }
            return List.of();
        }

        @Override
        public synchronized void markPublished(
                final String eventId,
                final String workerId,
                final Instant publishedAt) {
            state = State.PUBLISHED;
        }

        @Override
        public synchronized void markRetry(
                final String eventId,
                final String workerId,
                final Instant retryAt,
                final String error) {
            state = State.RETRY;
            nextAttemptAt = retryAt;
        }

        @Override
        public synchronized void markDead(
                final String eventId,
                final String workerId,
                final String error) {
            state = State.DEAD;
        }
    }

    private static final class MutableClock extends Clock {

        private Instant current;

        private MutableClock(final Instant current) {
            this.current = current;
        }

        private void advance(final Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
