package exchange.core2.core.simulation.outbox;

/**
 * Persistence or idempotency failure at the durable outbox boundary.
 */
public final class PortfolioOutboxException extends RuntimeException {

    PortfolioOutboxException(
            final String message,
            final Throwable cause) {
        super(message, cause);
    }

    PortfolioOutboxException(final String message) {
        super(message);
    }
}

