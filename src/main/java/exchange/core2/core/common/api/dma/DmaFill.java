package exchange.core2.core.common.api.dma;

/**
 * Immutable fill emitted for an incoming DMA order.
 *
 * @param makerOrderId          resting order matched by the incoming order
 * @param makerClientId         owner of the resting order
 * @param price                 maker price used for the fill
 * @param quantity              executed quantity
 * @param incomingOrderComplete whether this fill completed the incoming order
 * @param makerOrderComplete    whether this fill completed the maker order
 */
public record DmaFill(
        long makerOrderId,
        long makerClientId,
        long price,
        long quantity,
        boolean incomingOrderComplete,
        boolean makerOrderComplete) {
}
