package exchange.core2.core.common;

import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MatcherResultTest {

    @Test
    void resultRemainsImmutableAfterRingCommandAndMatcherEventChange() {
        MatcherTradeEvent event = MatcherTradeEvent.builder()
                .eventType(MatcherEventType.TRADE)
                .activeOrderCompleted(true)
                .matchedOrderId(41L)
                .matchedOrderUid(42L)
                .matchedOrderCompleted(false)
                .price(1_001L)
                .size(7L)
                .bidderHoldPrice(1_002L)
                .build();
        OrderCommand command = OrderCommand.builder()
                .command(OrderCommandType.PLACE_ORDER)
                .orderId(43L)
                .symbol(44)
                .price(1_001L)
                .size(7L)
                .uid(45L)
                .resultCode(CommandResultCode.SUCCESS)
                .matcherEvent(event)
                .build();

        MatcherResult result = MatcherResult.from(46L, command);

        command.orderId = 99L;
        command.resultCode = CommandResultCode.NEW;
        command.matcherEvent = null;
        event.price = 2_002L;
        event.size = 8L;

        assertEquals(46L, result.sequence());
        assertEquals(43L, result.orderId());
        assertEquals(CommandResultCode.SUCCESS, result.resultCode());
        assertEquals(1_001L, result.events().getFirst().price());
        assertEquals(7L, result.events().getFirst().size());
        assertThrows(UnsupportedOperationException.class,
                () -> result.events().add(result.events().getFirst()));
    }
    @Test
    void commandSlotsCopyNativeChainsAndRetainIndependentInFlightResults() {
        var first = MatcherResult.commandSlot();
        var second = MatcherResult.commandSlot();
        var event = MatcherTradeEvent.builder().eventType(MatcherEventType.TRADE)
                .matchedOrderId(7).matchedOrderUid(8).price(100).size(3).build();
        var command = OrderCommand.builder().command(OrderCommandType.PLACE_ORDER)
                .orderId(9).resultCode(CommandResultCode.SUCCESS).matcherEvent(event).build();
        first.capture(1, command);
        var firstEvent = first.events().getFirst();
        event.price = 200;
        command.orderId = 10;
        second.capture(2, command);
        assertEquals(100, firstEvent.price());
        assertEquals(9, first.orderId());
        assertEquals(200, second.events().getFirst().price());
        assertThrows(UnsupportedOperationException.class, () -> first.events().clear());
        first.clearCommandSlot();
        event.price = 300;
        first.capture(3, command);
        org.junit.jupiter.api.Assertions.assertSame(firstEvent, first.events().getFirst());
        assertEquals(300, firstEvent.price());
        assertEquals(200, second.events().getFirst().price());
        first.capture(first);
        assertEquals(1, first.events().size());
        command.matcherEvent = null;
        first.capture(4, command);
        assertEquals(0, first.events().size());
    }

    @Test
    void publicConstructorDetachesBorrowedEvents() {
        var slot = MatcherResult.commandSlot();
        var command = OrderCommand.builder().command(OrderCommandType.PLACE_ORDER)
                .resultCode(CommandResultCode.SUCCESS).matcherEvent(MatcherTradeEvent.builder()
                        .eventType(MatcherEventType.TRADE).price(100).size(2).build()).build();
        slot.capture(1, command);
        var stable = new MatcherResult(slot.sequence(), slot.command(), slot.orderId(), slot.symbol(),
                slot.price(), slot.size(), slot.reserveBidPrice(), slot.action(), slot.orderType(),
                slot.uid(), slot.timestamp(), slot.userCookie(), slot.resultCode(), slot.events(), slot.marketData());
        command.matcherEvent.price = 200;
        slot.capture(2, command);
        assertEquals(100, stable.events().getFirst().price());
        assertThrows(IllegalStateException.class, stable::clearCommandSlot);
    }
}

