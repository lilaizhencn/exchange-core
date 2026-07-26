package exchange.core2.core.common.api.dma;

/**
 * Immutable direct-market-access cancellation request.
 */
public record DmaCancelOrder(long orderId, long clientId, int symbol) {

    public DmaCancelOrder {
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
