package exchange.core2.core.common.api.dma;

import java.util.Objects;

/**
 * Result returned by the lifecycle service.
 */
public record DmaLifecycleResult(
        long deliveryId,
        DmaOrderResult commandResult,
        DmaOrderState orderState,
        boolean duplicateDelivery) {

    public DmaLifecycleResult {
        if (deliveryId <= 0) {
            throw new IllegalArgumentException("deliveryId must be positive");
        }
        Objects.requireNonNull(commandResult, "commandResult");
        Objects.requireNonNull(orderState, "orderState");
        if (commandResult.orderId() != orderState.order().orderId()) {
            throw new IllegalArgumentException("command result and lifecycle state order IDs must match");
        }
    }

    public DmaLifecycleResult asDuplicateDelivery() {
        return duplicateDelivery
                ? this
                : new DmaLifecycleResult(deliveryId, commandResult, orderState, true);
    }
}
