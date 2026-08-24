package exchange.core2.core.common;

import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record MatcherResult(
        long sequence,
        OrderCommandType command,
        long orderId,
        int symbol,
        long price,
        long size,
        long reserveBidPrice,
        OrderAction action,
        OrderType orderType,
        long uid,
        long timestamp,
        int userCookie,
        CommandResultCode resultCode,
        List<MatcherEvent> events,
        MarketData marketData) {

    public MatcherResult {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(resultCode, "resultCode");
        events = List.copyOf(events);
        marketData = Objects.requireNonNull(marketData, "marketData");
    }

    public static MatcherResult from(long sequence, OrderCommand command) {
        Objects.requireNonNull(command, "command");
        List<MatcherEvent> events = new ArrayList<>();
        command.processMatcherEvents(event -> events.add(new MatcherEvent(
                event.eventType,
                event.section,
                event.activeOrderCompleted,
                event.matchedOrderId,
                event.matchedOrderUid,
                event.matchedOrderCompleted,
                event.price,
                event.size,
                event.bidderHoldPrice)));
        return new MatcherResult(
                sequence,
                command.command,
                command.orderId,
                command.symbol,
                command.price,
                command.size,
                command.reserveBidPrice,
                command.action,
                command.orderType,
                command.uid,
                command.timestamp,
                command.userCookie,
                command.resultCode,
                events,
                MarketData.from(command.marketData));
    }

    public record MatcherEvent(
            MatcherEventType eventType,
            int section,
            boolean activeOrderCompleted,
            long matchedOrderId,
            long matchedOrderUid,
            boolean matchedOrderCompleted,
            long price,
            long size,
            long bidderHoldPrice) {

        public MatcherEvent {
            Objects.requireNonNull(eventType, "eventType");
        }
    }

    public record MarketData(List<Level> asks, List<Level> bids, long timestamp, long referenceSequence) {

        private static final MarketData EMPTY = new MarketData(List.of(), List.of(), 0L, 0L);

        public MarketData {
            asks = List.copyOf(asks);
            bids = List.copyOf(bids);
        }

        private static MarketData from(L2MarketData marketData) {
            if (marketData == null) {
                return EMPTY;
            }
            List<Level> asks = new ArrayList<>(marketData.askSize);
            for (int i = 0; i < marketData.askSize; i++) {
                asks.add(new Level(marketData.askPrices[i], marketData.askVolumes[i], marketData.askOrders[i]));
            }
            List<Level> bids = new ArrayList<>(marketData.bidSize);
            for (int i = 0; i < marketData.bidSize; i++) {
                bids.add(new Level(marketData.bidPrices[i], marketData.bidVolumes[i], marketData.bidOrders[i]));
            }
            return new MarketData(asks, bids, marketData.timestamp, marketData.referenceSeq);
        }
    }

    public record Level(long price, long volume, long orders) {
    }
}
