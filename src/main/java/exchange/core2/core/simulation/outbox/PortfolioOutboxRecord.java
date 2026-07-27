package exchange.core2.core.simulation.outbox;

import exchange.core2.core.simulation.http.EmporiaPortfolioHttpEvent;

/**
 * One leased durable event.
 */
public record PortfolioOutboxRecord(
        long sequenceId,
        EmporiaPortfolioHttpEvent event,
        int attemptCount) {

    public PortfolioOutboxRecord {
        if (sequenceId <= 0) {
            throw new IllegalArgumentException(
                    "sequenceId must be positive");
        }
        if (event == null) {
            throw new NullPointerException("event");
        }
        if (attemptCount <= 0) {
            throw new IllegalArgumentException(
                    "attemptCount must be positive");
        }
    }
}

