package exchange.core2.core.common.api.dma;

import exchange.core2.core.common.OrderAction;

/**
 * Common terms of a new DMA order.
 */
public sealed interface DmaNewOrder extends DmaDeliveryRequest
        permits DmaLimitOrder, DmaProtectedMarketOrder {

    long clientId();

    int symbol();

    OrderAction side();

    /**
     * Limit price or IOC protection price.
     */
    long price();

    long quantity();
}
