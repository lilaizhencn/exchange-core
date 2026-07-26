package exchange.core2.core.common.api.dma;

/**
 * Immutable direct-market-access cancellation request.
 */
public record DmaCancelOrder(
        long deliveryId,
        long orderId,
        long clientId,
        int symbol) implements DmaDeliveryRequest {

    /**
     * Uses the order identifier as the delivery identifier.
     */
    public DmaCancelOrder(final long orderId, final long clientId, final int symbol) {
        this(orderId, orderId, clientId, symbol);
    }

    public DmaCancelOrder {
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
    }
}
