package exchange.core2.core.common.api.dma;

import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable result of a DMA submit or cancel command.
 */
public record DmaOrderResult(
        long orderId,
        CommandResultCode resultCode,
        List<DmaFill> fills,
        long cancelledQuantity,
        long rejectedQuantity) {

    public DmaOrderResult {
        Objects.requireNonNull(resultCode, "resultCode");
        fills = List.copyOf(fills);
    }

    /**
     * Snapshots a mutable matching command while it is still owned by the
     * results processor. Matcher-event traversal order is retained verbatim.
     */
    public static DmaOrderResult from(final OrderCommand command) {
        final List<DmaFill> fills = new ArrayList<>();
        long cancelledQuantity = 0L;
        long rejectedQuantity = 0L;

        MatcherTradeEvent event = command.matcherEvent;
        while (event != null) {
            if (event.eventType == MatcherEventType.TRADE) {
                fills.add(new DmaFill(
                        event.matchedOrderId,
                        event.matchedOrderUid,
                        event.price,
                        event.size,
                        event.activeOrderCompleted,
                        event.matchedOrderCompleted));
            } else if (event.eventType == MatcherEventType.REDUCE) {
                cancelledQuantity = Math.addExact(cancelledQuantity, event.size);
            } else if (event.eventType == MatcherEventType.REJECT) {
                rejectedQuantity = Math.addExact(rejectedQuantity, event.size);
            }
            event = event.nextEvent;
        }

        return new DmaOrderResult(
                command.orderId,
                command.resultCode,
                fills,
                cancelledQuantity,
                rejectedQuantity);
    }
}
