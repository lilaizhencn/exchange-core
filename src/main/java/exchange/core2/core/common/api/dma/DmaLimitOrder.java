package exchange.core2.core.common.api.dma;

import exchange.core2.core.common.OrderAction;

import java.util.Objects;

/**
 * Immutable direct-market-access request for a GTC limit order.
 */
public record DmaLimitOrder(
        long deliveryId,
        long orderId,
        long clientId,
        int symbol,
        OrderAction side,
        long price,
        long quantity) implements DmaDeliveryRequest {

    /**
     * Uses the order identifier as the delivery identifier.
     */
    public DmaLimitOrder(
            final long orderId,
            final long clientId,
            final int symbol,
            final OrderAction side,
            final long price,
            final long quantity) {
        this(orderId, orderId, clientId, symbol, side, price, quantity);
    }

    public DmaLimitOrder {
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
        if (price <= 0) {
            throw new IllegalArgumentException("price must be positive");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
