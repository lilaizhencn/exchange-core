package exchange.core2.core.simulation.outbox;

import java.time.Duration;
import java.util.Objects;

/**
 * Lease, polling, and retry settings for a portfolio outbox publisher.
 */
public record PortfolioOutboxConfiguration(
        String workerId,
        int batchSize,
        Duration pollInterval,
        Duration leaseDuration,
        Duration initialRetryDelay,
        Duration maximumRetryDelay) {

    public PortfolioOutboxConfiguration {
        if (workerId == null || workerId.isBlank()
                || workerId.length() > 100) {
            throw new IllegalArgumentException(
                    "workerId must contain 1 to 100 characters");
        }
        if (batchSize <= 0 || batchSize > 1_000) {
            throw new IllegalArgumentException(
                    "batchSize must be between 1 and 1000");
        }
        requirePositive(pollInterval, "pollInterval");
        requirePositive(leaseDuration, "leaseDuration");
        requirePositive(initialRetryDelay, "initialRetryDelay");
        requirePositive(maximumRetryDelay, "maximumRetryDelay");
        if (maximumRetryDelay.compareTo(initialRetryDelay) < 0) {
            throw new IllegalArgumentException(
                    "maximumRetryDelay must not be shorter than initialRetryDelay");
        }
    }

    public static PortfolioOutboxConfiguration defaults(
            final String workerId) {
        return new PortfolioOutboxConfiguration(
                workerId,
                100,
                Duration.ofMillis(250),
                Duration.ofSeconds(30),
                Duration.ofMillis(250),
                Duration.ofMinutes(5));
    }

    private static void requirePositive(
            final Duration value,
            final String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

