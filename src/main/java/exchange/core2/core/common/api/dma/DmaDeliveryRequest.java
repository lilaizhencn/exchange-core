package exchange.core2.core.common.api.dma;

/**
 * A DMA request carrying a stable delivery identifier for idempotent handling.
 */
public sealed interface DmaDeliveryRequest permits DmaLimitOrder, DmaCancelOrder {

    long deliveryId();

    long orderId();
}
