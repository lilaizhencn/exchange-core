package exchange.core2.core.common.api.dma;

import exchange.core2.core.common.OrderAction;

import java.util.Objects;

/**
 * Immediate-or-cancel marketable order with a hard execution-price boundary.
 *
 * <p>A bid never executes above {@code protectionPrice}; an ask never executes
 * below it. Any unfilled quantity is rejected instead of resting.</p>
 */
public record DmaProtectedMarketOrder(
        long deliveryId,
        long orderId,
        long clientId,
        int symbol,
        OrderAction side,
        long protectionPrice,
        long quantity) implements DmaNewOrder {

    public DmaProtectedMarketOrder {
        if (deliveryId <= 0) {
            throw new IllegalArgumentException("deliveryId must be positive");
        }
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId must be positive");
        }
        if (clientId <= 0) {
            throw new IllegalArgumentException("clientId must be positive");
        }
        if (symbol < 0) {
            throw new IllegalArgumentException("symbol must not be negative");
        }
        Objects.requireNonNull(side, "side");
        if (protectionPrice <= 0) {
            throw new IllegalArgumentException("protectionPrice must be positive");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }

    @Override
    public long price() {
        return protectionPrice;
    }
}
