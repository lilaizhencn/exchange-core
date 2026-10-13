package exchange.core2.core.common;

import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable results from from()/constructors; reusable results belong to one host command slot. */
public final class MatcherResult {
    private long sequence;
    private OrderCommandType command;
    private long orderId;
    private int symbol;
    private long price;
    private long size;
    private long reserveBidPrice;
    private OrderAction action;
    private OrderType orderType;
    private long uid;
    private long timestamp;
    private int userCookie;
    private CommandResultCode resultCode;
    private List<MatcherEvent> events;
    private MarketData marketData;
    private final EventSlots eventSlots;

    public MatcherResult(long sequence, OrderCommandType command, long orderId, int symbol, long price, long size, long reserveBidPrice, OrderAction action, OrderType orderType, long uid, long timestamp, int userCookie, CommandResultCode resultCode, List<MatcherEvent> events, MarketData marketData) {
        eventSlots = null;
        this.sequence = sequence;
        this.command = Objects.requireNonNull(command, "command");
        this.orderId = orderId;
        this.symbol = symbol;
        this.price = price;
        this.size = size;
        this.reserveBidPrice = reserveBidPrice;
        this.action = action;
        this.orderType = orderType;
        this.uid = uid;
        this.timestamp = timestamp;
        this.userCookie = userCookie;
        this.resultCode = Objects.requireNonNull(resultCode, "resultCode");
        List<MatcherEvent> stableEvents = new ArrayList<>(events.size());
        for (int index = 0; index < events.size(); index++) stableEvents.add(events.get(index).immutableValue());
        this.events = List.copyOf(stableEvents);
        this.marketData = Objects.requireNonNull(marketData, "marketData");
    }

    private MatcherResult() {
        eventSlots = new EventSlots();
        events = eventSlots;
        marketData = MarketData.EMPTY;
    }

    public static MatcherResult commandSlot() { return new MatcherResult(); }
    public boolean isCommandSlot() { return eventSlots != null; }

    /** Copy before exchange-core returns its native event chain to SharedPool. */
    public void capture(long sequence, OrderCommand source) {
        if (eventSlots == null) throw new IllegalStateException("matcher result is not a command slot");
        this.sequence = sequence;
        command = source.command;
        orderId = source.orderId;
        symbol = source.symbol;
        price = source.price;
        size = source.size;
        reserveBidPrice = source.reserveBidPrice;
        action = source.action;
        orderType = source.orderType;
        uid = source.uid;
        timestamp = source.timestamp;
        userCookie = source.userCookie;
        resultCode = source.resultCode;
        marketData = MarketData.from(source.marketData);
        eventSlots.count = 0;
        for (MatcherTradeEvent event = source.matcherEvent; event != null; event = event.nextEvent)
            eventSlots.append(event);
    }

    public void capture(MatcherResult source) {
        if (source == this) return;
        if (eventSlots == null) throw new IllegalStateException("matcher result is not a command slot");
        sequence = source.sequence;
        command = source.command;
        orderId = source.orderId;
        symbol = source.symbol;
        price = source.price;
        size = source.size;
        reserveBidPrice = source.reserveBidPrice;
        action = source.action;
        orderType = source.orderType;
        uid = source.uid;
        timestamp = source.timestamp;
        userCookie = source.userCookie;
        resultCode = source.resultCode;
        marketData = source.marketData;
        eventSlots.count = 0;
        for (int index = 0; index < source.events.size(); index++) eventSlots.append(source.events.get(index));
    }

    /** Retain only events in a longer-lived response slot after a settlement slot retires. */
    public void captureEvents(List<MatcherEvent> source) {
        if (eventSlots == null) throw new IllegalStateException("matcher result is not a command slot");
        if (source == events) return;
        eventSlots.count = 0;
        for (int index = 0; index < source.size(); index++) eventSlots.append(source.get(index));
    }

    public void clearCommandSlot() {
        if (eventSlots == null) throw new IllegalStateException("cannot clear an immutable matcher result");
        eventSlots.count = 0;
        command = null; action = null; orderType = null; resultCode = null;
        marketData = MarketData.EMPTY;
        sequence = orderId = uid = timestamp = 0;
    }

    public long sequence() { return sequence; }
    public OrderCommandType command() { return command; }
    public long orderId() { return orderId; }
    public int symbol() { return symbol; }
    public long price() { return price; }
    public long size() { return size; }
    public long reserveBidPrice() { return reserveBidPrice; }
    public OrderAction action() { return action; }
    public OrderType orderType() { return orderType; }
    public long uid() { return uid; }
    public long timestamp() { return timestamp; }
    public int userCookie() { return userCookie; }
    public CommandResultCode resultCode() { return resultCode; }
    public List<MatcherEvent> events() { return events; }
    public MarketData marketData() { return marketData; }

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

    private static final class EventSlots extends java.util.AbstractList<MatcherEvent> implements java.util.RandomAccess {
        private MatcherEvent[] values = new MatcherEvent[4];
        private int count;
        private MatcherEvent next() {
            if (count == values.length) values = java.util.Arrays.copyOf(values, values.length * 2);
            MatcherEvent value = values[count];
            if (value == null) { values[count] = value = new MatcherEvent(); }
            count++;
            return value;
        }
        void append(MatcherTradeEvent source) {
            MatcherEvent target = next();
            target.eventType = source.eventType;
            target.section = source.section;
            target.activeOrderCompleted = source.activeOrderCompleted;
            target.matchedOrderId = source.matchedOrderId;
            target.matchedOrderUid = source.matchedOrderUid;
            target.matchedOrderCompleted = source.matchedOrderCompleted;
            target.price = source.price;
            target.size = source.size;
            target.bidderHoldPrice = source.bidderHoldPrice;
        }
        void append(MatcherEvent source) {
            MatcherEvent target = next();
            target.eventType = source.eventType;
            target.section = source.section;
            target.activeOrderCompleted = source.activeOrderCompleted;
            target.matchedOrderId = source.matchedOrderId;
            target.matchedOrderUid = source.matchedOrderUid;
            target.matchedOrderCompleted = source.matchedOrderCompleted;
            target.price = source.price;
            target.size = source.size;
            target.bidderHoldPrice = source.bidderHoldPrice;
        }
        @Override public MatcherEvent get(int index) {
            if (index < 0 || index >= count) throw new IndexOutOfBoundsException(index);
            return values[index];
        }
        @Override public int size() { return count; }
    }

    public static final class MatcherEvent {
        private final boolean commandSlot;
        private MatcherEventType eventType;
        private int section;
        private boolean activeOrderCompleted;
        private long matchedOrderId;
        private long matchedOrderUid;
        private boolean matchedOrderCompleted;
        private long price;
        private long size;
        private long bidderHoldPrice;
        private MatcherEvent() { commandSlot = true; }
        public MatcherEvent(MatcherEventType eventType, int section, boolean activeOrderCompleted, long matchedOrderId, long matchedOrderUid, boolean matchedOrderCompleted, long price, long size, long bidderHoldPrice) {
            commandSlot = false;
            this.eventType = Objects.requireNonNull(eventType, "eventType");
            this.section = section;
            this.activeOrderCompleted = activeOrderCompleted;
            this.matchedOrderId = matchedOrderId;
            this.matchedOrderUid = matchedOrderUid;
            this.matchedOrderCompleted = matchedOrderCompleted;
            this.price = price;
            this.size = size;
            this.bidderHoldPrice = bidderHoldPrice;
        }
        public MatcherEventType eventType() { return eventType; }
        public int section() { return section; }
        public boolean activeOrderCompleted() { return activeOrderCompleted; }
        public long matchedOrderId() { return matchedOrderId; }
        public long matchedOrderUid() { return matchedOrderUid; }
        public boolean matchedOrderCompleted() { return matchedOrderCompleted; }
        public long price() { return price; }
        public long size() { return size; }
        public long bidderHoldPrice() { return bidderHoldPrice; }
        private MatcherEvent immutableValue() {
            return commandSlot ? new MatcherEvent(eventType, section, activeOrderCompleted, matchedOrderId, matchedOrderUid, matchedOrderCompleted, price, size, bidderHoldPrice) : this;
        }
        @Override public boolean equals(Object other) {
            return other instanceof MatcherEvent value && Objects.equals(eventType, value.eventType) && section == value.section && activeOrderCompleted == value.activeOrderCompleted && matchedOrderId == value.matchedOrderId && matchedOrderUid == value.matchedOrderUid && matchedOrderCompleted == value.matchedOrderCompleted && price == value.price && size == value.size && bidderHoldPrice == value.bidderHoldPrice;
        }
        @Override public int hashCode() { return Objects.hash(eventType, section, activeOrderCompleted, matchedOrderId, matchedOrderUid, matchedOrderCompleted, price, size, bidderHoldPrice); }
        @Override public String toString() { return "MatcherEvent[" + eventType + ", " + matchedOrderId + ", " + price + ", " + size + "]"; }
    }

    @Override public boolean equals(Object other) {
        return other instanceof MatcherResult value && sequence == value.sequence && Objects.equals(command, value.command) && orderId == value.orderId && symbol == value.symbol && price == value.price && size == value.size && reserveBidPrice == value.reserveBidPrice && Objects.equals(action, value.action) && Objects.equals(orderType, value.orderType) && uid == value.uid && timestamp == value.timestamp && userCookie == value.userCookie && Objects.equals(resultCode, value.resultCode) && Objects.equals(events, value.events) && Objects.equals(marketData, value.marketData);
    }
    @Override public int hashCode() { return Objects.hash(sequence, command, orderId, symbol, price, size, reserveBidPrice, action, orderType, uid, timestamp, userCookie, resultCode, events, marketData); }
    @Override public String toString() { return "MatcherResult[sequence=" + sequence + ", command=" + command + ", orderId=" + orderId + ", resultCode=" + resultCode + ", events=" + events + "]"; }

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
