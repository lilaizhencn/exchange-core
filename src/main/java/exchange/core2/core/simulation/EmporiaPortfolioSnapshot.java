package exchange.core2.core.simulation;

import java.util.Map;
import java.util.Objects;

/**
 * Risk-engine balances published to the Emporia portfolio boundary.
 */
public record EmporiaPortfolioSnapshot(
        long deliveryId,
        long clientId,
        Map<Integer, Long> availableBalances,
        EmporiaPortfolioChange change) {

    /**
     * Defaults to {@link EmporiaPortfolioChange#SETTLED}, the conservative
     * choice: a snapshot of unstated kind is never collapsed away.
     */
    public EmporiaPortfolioSnapshot(
            final long deliveryId,
            final long clientId,
            final Map<Integer, Long> availableBalances) {
        this(deliveryId, clientId, availableBalances,
                EmporiaPortfolioChange.SETTLED);
    }

    public EmporiaPortfolioSnapshot {
        if (deliveryId < 0) {
            throw new IllegalArgumentException(
                    "deliveryId must not be negative");
        }
        if (clientId <= 0) {
            throw new IllegalArgumentException("clientId must be positive");
        }
        Objects.requireNonNull(availableBalances, "availableBalances");
        Objects.requireNonNull(change, "change");
        availableBalances = Map.copyOf(availableBalances);
    }
}
