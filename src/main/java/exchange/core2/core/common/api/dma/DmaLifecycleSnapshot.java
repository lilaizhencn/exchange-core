package exchange.core2.core.common.api.dma;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable lifecycle checkpoint used to restore state and delivery deduplication.
 */
public record DmaLifecycleSnapshot(
        Map<Long, DmaOrderState> orders,
        List<CompletedDelivery> completedDeliveries) {

    public DmaLifecycleSnapshot {
        orders = Map.copyOf(orders);
        completedDeliveries = List.copyOf(completedDeliveries);
    }

    /**
     * A completed delivery and its original response.
     */
    public record CompletedDelivery(
            DmaDeliveryRequest request,
            DmaLifecycleResult result) {

        public CompletedDelivery {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(result, "result");
            if (request.deliveryId() != result.deliveryId()) {
                throw new IllegalArgumentException("request and result delivery IDs must match");
            }
            if (request.orderId() != result.orderState().order().orderId()) {
                throw new IllegalArgumentException("request and result order IDs must match");
            }
        }
    }
}
