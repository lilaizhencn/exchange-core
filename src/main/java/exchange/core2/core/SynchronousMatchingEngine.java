package exchange.core2.core;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.IOrder;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.MatcherResult;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.api.reports.StateHashReportQuery;
import exchange.core2.core.common.api.reports.StateHashReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.OrdersProcessingConfiguration;
import exchange.core2.core.processors.MatchingEngineRouter;
import exchange.core2.core.processors.SharedPool;
import exchange.core2.core.processors.journaling.ISerializationProcessor;
import exchange.core2.core.utils.HashingUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Single-owner matching engine for deterministic state-machine hosts.
 *
 * <p>This entry point executes the same {@link MatchingEngineRouter} and order-book implementation as
 * {@link ExchangeCore}, but deliberately does not create a Disruptor, worker threads, futures, or a second
 * sequencer. The caller owns ordering and must invoke every method from one thread.</p>
 */
public final class SynchronousMatchingEngine implements AutoCloseable {

    private static final int EVENT_POOL_CHAINS = 32;
    private static final int EVENT_CHAIN_LENGTH = 1_024;

    private final MatchingEngineRouter router;
    private final SharedPool sharedPool;
    private final ISerializationProcessor serializationProcessor;
    private final OrderCommand command = new OrderCommand();
    private long sequence;
    private Thread owner;
    private boolean closed;

    public SynchronousMatchingEngine(final ExchangeConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        final OrdersProcessingConfiguration orders = configuration.getOrdersProcessingCfg();
        if (!orders.getRiskProcessingMode().isMatchingOnly()) {
            throw new IllegalArgumentException("synchronous matching requires MATCHING_ONLY risk mode");
        }
        if (configuration.getPerformanceCfg().getMatchingEnginesNum() != 1
                || configuration.getPerformanceCfg().getRiskEnginesNum() != 0) {
            throw new IllegalArgumentException("synchronous matching requires exactly one matcher and no risk engine");
        }
        if (configuration.getSerializationCfg().isEnableJournaling()) {
            throw new IllegalArgumentException("synchronous matching does not support exchange-core journaling");
        }
        serializationProcessor = configuration.getSerializationCfg()
                .getSerializationProcessorFactory().apply(configuration);
        sharedPool = new SharedPool(EVENT_POOL_CHAINS, 8, EVENT_CHAIN_LENGTH);
        router = new MatchingEngineRouter(0, 1, serializationProcessor,
                configuration.getPerformanceCfg().getOrderBookFactory(), sharedPool, configuration);
    }

    public CommandResultCode registerSymbol(final CoreSymbolSpecification specification) {
        assertOwner();
        return router.registerSymbol(specification);
    }

    public MatcherResult place(final long timestamp, final long orderId, final int userCookie,
                               final long price, final long reservedBidPrice, final long size,
                               final OrderAction action, final OrderType orderType,
                               final int symbol, final long uid) {
        prepare(OrderCommandType.PLACE_ORDER, timestamp, orderId, symbol, uid);
        command.userCookie = userCookie;
        command.price = price;
        command.reserveBidPrice = reservedBidPrice;
        command.size = size;
        command.action = Objects.requireNonNull(action, "action");
        command.orderType = Objects.requireNonNull(orderType, "orderType");
        return execute();
    }

    public MatcherResult move(final long timestamp, final long price, final long orderId,
                              final int symbol, final long uid) {
        prepare(OrderCommandType.MOVE_ORDER, timestamp, orderId, symbol, uid);
        command.price = price;
        return execute();
    }

    public MatcherResult replace(final long timestamp, final long price, final long reservedBidPrice,
                                 final long quantity, final OrderAction action, final long orderId,
                                 final int symbol, final long uid) {
        prepare(OrderCommandType.REPLACE_ORDER, timestamp, orderId, symbol, uid);
        command.price = price;
        command.reserveBidPrice = reservedBidPrice;
        command.size = quantity;
        command.action = Objects.requireNonNull(action, "action");
        return execute();
    }

    public MatcherResult cancel(final long timestamp, final long orderId, final int symbol, final long uid) {
        prepare(OrderCommandType.CANCEL_ORDER, timestamp, orderId, symbol, uid);
        return execute();
    }

    public L2MarketData orderBook(final int symbol, final int depth) {
        prepare(OrderCommandType.ORDER_BOOK_REQUEST, 0, 0, symbol, 0);
        command.size = depth;
        MatcherResult ignored = execute();
        return toMarketData(ignored.marketData());
    }

    public List<OpenOrder> openOrders() {
        assertOwner();
        final ArrayList<OpenOrder> orders = new ArrayList<>();
        router.getOrderBooks().forEachKeyValue((symbol, book) -> {
            book.askOrdersStream(true).forEach(order -> orders.add(openOrder(symbol, order, OrderAction.ASK)));
            book.bidOrdersStream(true).forEach(order -> orders.add(openOrder(symbol, order, OrderAction.BID)));
        });
        orders.sort(Comparator.comparingInt(OpenOrder::symbolId).thenComparingLong(OpenOrder::orderId));
        return List.copyOf(orders);
    }

    public StateHashReportResult stateHashReport() {
        assertOwner();
        return new StateHashReportQuery().process(router).orElseThrow();
    }

    public int bookStateHash() {
        assertOwner();
        return HashingUtils.stateHash(router.getOrderBooks());
    }

    public boolean storeSnapshot(final long snapshotId, final long coreSequence, final long timestamp) {
        assertOwner();
        return serializationProcessor.storeData(snapshotId, coreSequence, timestamp,
                ISerializationProcessor.SerializedModuleType.MATCHING_ENGINE_ROUTER, 0, router);
    }

    private MatcherResult execute() {
        final long currentSequence = sequence = Math.incrementExact(sequence);
        router.processOrder(currentSequence, command);
        final MatcherResult result = MatcherResult.from(currentSequence, command);
        recycleEvents();
        command.marketData = null;
        return result;
    }

    private void prepare(final OrderCommandType type, final long timestamp, final long orderId,
                         final int symbol, final long uid) {
        assertOwner();
        recycleEvents();
        command.marketData = null;
        command.command = type;
        command.resultCode = CommandResultCode.NEW;
        command.timestamp = timestamp;
        command.orderId = orderId;
        command.symbol = symbol;
        command.uid = uid;
        command.price = 0;
        command.reserveBidPrice = 0;
        command.size = 0;
        command.userCookie = 0;
        command.action = null;
        command.orderType = null;
        command.serviceFlags = 0;
        command.eventsGroup = 0;
        command.correlationId = 0;
    }

    private void recycleEvents() {
        final MatcherTradeEvent events = command.matcherEvent;
        if (events != null) {
            command.matcherEvent = null;
            sharedPool.putChain(events);
        }
    }

    private void assertOwner() {
        if (closed) {
            throw new IllegalStateException("synchronous matching engine is closed");
        }
        final Thread current = Thread.currentThread();
        if (owner == null) {
            owner = current;
        } else if (owner != current) {
            throw new IllegalStateException("synchronous matching engine accessed by a non-owner thread");
        }
    }

    private static OpenOrder openOrder(final int symbol, final IOrder order, final OrderAction action) {
        return new OpenOrder(symbol, order.getOrderId(), order.getUid(), action, order.getPrice(),
                order.getSize(), order.getFilled(), order.getReserveBidPrice());
    }

    private static L2MarketData toMarketData(final MatcherResult.MarketData source) {
        final L2MarketData result = new L2MarketData(source.asks().size(), source.bids().size());
        result.askSize = source.asks().size();
        for (int index = 0; index < result.askSize; index++) {
            final MatcherResult.Level level = source.asks().get(index);
            result.askPrices[index] = level.price();
            result.askVolumes[index] = level.volume();
            result.askOrders[index] = level.orders();
        }
        result.bidSize = source.bids().size();
        for (int index = 0; index < result.bidSize; index++) {
            final MatcherResult.Level level = source.bids().get(index);
            result.bidPrices[index] = level.price();
            result.bidVolumes[index] = level.volume();
            result.bidOrders[index] = level.orders();
        }
        result.timestamp = source.timestamp();
        result.referenceSeq = source.referenceSequence();
        return result;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        assertOwner();
        recycleEvents();
        closed = true;
    }

    public record OpenOrder(int symbolId, long orderId, long uid, OrderAction action,
                            long price, long size, long filled, long reserveBidPrice) {
    }
}
