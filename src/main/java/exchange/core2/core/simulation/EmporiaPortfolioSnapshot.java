package exchange.core2.core.simulation;

import java.util.Map;
import java.util.Objects;

/**
 * Risk-engine balances published to the Emporia portfolio boundary.
 */
public record EmporiaPortfolioSnapshot(
        long deliveryId,
        long clientId,
        Map<Integer, Long> availableBalances) {

    public EmporiaPortfolioSnapshot {
        if (deliveryId < 0) {
            throw new IllegalArgumentException(
                    "deliveryId must not be negative");
        }
        if (clientId <= 0) {
            throw new IllegalArgumentException("clientId must be positive");
        }
        Objects.requireNonNull(availableBalances, "availableBalances");
        availableBalances = Map.copyOf(availableBalances);
    }
}
