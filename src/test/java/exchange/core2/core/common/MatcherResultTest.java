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
}
