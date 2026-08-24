package exchange.core2.core.orderbook;

import exchange.core2.core.ExchangeCore;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.processors.SharedPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatcherEventPoolingTest {

    @Test
    void pooledEventIsDetachedAndResetBeforeReuse() {
        assertTrue(ExchangeCore.EVENTS_POOLING);
        SharedPool pool = new SharedPool(1, 1, 2);
        MatcherTradeEvent chain = pool.getChain();
        chain.eventType = MatcherEventType.TRADE;
        chain.matchedOrderUid = 91L;
        chain.nextEvent.eventType = MatcherEventType.REJECT;
        pool.putChain(chain);

        OrderBookEventsHelper helper = new OrderBookEventsHelper(pool::getChain);
        OrderCommand order = OrderCommand.builder()
                .orderId(1L)
                .uid(2L)
                .price(3L)
                .size(4L)
                .reserveBidPrice(5L)
                .build();

        MatcherTradeEvent event = helper.sendReduceEvent(order, 4L, true);

        assertEquals(MatcherEventType.REDUCE, event.eventType);
        assertEquals(0L, event.matchedOrderUid);
        assertNull(event.nextEvent);
    }
}
