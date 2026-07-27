package exchange.core2.core.simulation;

import java.util.Map;
import java.util.Objects;

/**
 * One-time portfolio import used to create and fund a risk-managed client.
 *
 * @param clientId              exchange-core user identifier
 * @param firstTransactionId    transaction ID assigned to the first asset;
 *                              following assets use consecutive IDs
 * @param balances              asset or cash account ID to available balance
 */
public record EmporiaPortfolioSeed(
        long clientId,
        long firstTransactionId,
        Map<Integer, Long> balances) {

    public EmporiaPortfolioSeed {
        if (clientId <= 0) {
            throw new IllegalArgumentException("clientId must be positive");
        }
        if (firstTransactionId <= 0) {
            throw new IllegalArgumentException(
                    "firstTransactionId must be positive");
        }
        Objects.requireNonNull(balances, "balances");
        balances.forEach((assetId, balance) -> {
            if (assetId == null || assetId < 0) {
                throw new IllegalArgumentException(
                        "portfolio asset IDs must not be negative");
            }
            if (balance == null || balance < 0) {
                throw new IllegalArgumentException(
                        "portfolio balances must not be negative");
            }
        });
        if (!balances.isEmpty()) {
            Math.addExact(firstTransactionId, balances.size() - 1L);
        }
        balances = Map.copyOf(balances);
    }
}
