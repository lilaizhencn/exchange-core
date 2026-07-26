package exchange.core2.core.common.api.dma;

import exchange.core2.core.common.OrderAction;

import java.util.Objects;

/**
 * Atomically replaces the price and total quantity of a live DMA limit order.
 *
 * <p>The new total quantity includes quantities already filled. The matcher
 * rejects the replacement without changing the order when
 * {@code newQuantity <= cumulativeFilledQuantity}.</p>
 */
public record DmaReplaceOrder(
        long deliveryId,
        long orderId,
        long clientId,
        int symbol,
        OrderAction side,
        long newPrice,
        long newQuantity) implements DmaDeliveryRequest {

    public DmaReplaceOrder {
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
        if (newPrice <= 0) {
            throw new IllegalArgumentException("newPrice must be positive");
        }
        if (newQuantity <= 0) {
            throw new IllegalArgumentException("newQuantity must be positive");
        }
    }
}
