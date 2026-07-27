package exchange.core2.core.simulation.outbox;

import exchange.core2.core.simulation.http.EmporiaPortfolioHttpEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Durable event store used by the asynchronous publisher.
 */
public interface PortfolioOutboxStore {

    void enqueue(EmporiaPortfolioHttpEvent event);

    List<PortfolioOutboxRecord> claim(
            String workerId,
            int batchSize,
            Instant now,
            Duration leaseDuration);

    void markPublished(
            String eventId,
            String workerId,
            Instant publishedAt);

    void markRetry(
            String eventId,
            String workerId,
            Instant nextAttemptAt,
            String error);

    void markDead(
            String eventId,
            String workerId,
            String error);
}

