package exchange.core2.core.common.api.dma;

/**
 * Lifecycle state of a DMA limit order.
 */
public enum DmaOrderStatus {
    NEW,
    LIVE,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED;

    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED || this == REJECTED;
    }
}
