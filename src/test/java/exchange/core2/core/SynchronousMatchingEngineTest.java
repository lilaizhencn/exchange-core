package exchange.core2.core;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.OrdersProcessingConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SynchronousMatchingEngineTest {

    private static final int SYMBOL = 10_001;
    private static final CoreSymbolSpecification SPECIFICATION = CoreSymbolSpecification.builder()
            .symbolId(SYMBOL)
            .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
            .baseCurrency(1)
            .quoteCurrency(2)
            .baseScaleK(1)
            .quoteScaleK(1)
            .makerFee(0)
            .takerFee(0)
            .build();

    @Test
    void executesPriceTimeMatchingAndQueriesOnTheCallingThread() {
        try (SynchronousMatchingEngine engine = new SynchronousMatchingEngine(configuration())) {
            assertEquals(CommandResultCode.SUCCESS, engine.registerSymbol(SPECIFICATION));
            var ask = engine.place(
                    1, 101, 0, 100, 0, 5, OrderAction.ASK, OrderType.GTC, SYMBOL, 11);
            assertEquals(CommandResultCode.SUCCESS, ask.resultCode());
            assertEquals(1, ask.sequence());

            var bid = engine.place(
                    2, 102, 0, 100, 100, 2, OrderAction.BID, OrderType.GTC, SYMBOL, 12);

            assertEquals(CommandResultCode.SUCCESS, bid.resultCode());
            assertEquals(2, bid.sequence());
            assertEquals(1, bid.events().size());
            assertEquals(101, bid.events().getFirst().matchedOrderId());
            assertEquals(2, bid.events().getFirst().size());
            assertEquals(1, engine.openOrders().size());
            assertEquals(2, engine.openOrders().getFirst().filled());
            assertEquals(3, engine.orderBook(SYMBOL, 8).askVolumes[0]);
        }
    }

    @Test
    void rejectsAccessFromAnotherOwnerThread() throws Exception {
        try (SynchronousMatchingEngine engine = new SynchronousMatchingEngine(configuration())) {
            assertEquals(CommandResultCode.SUCCESS, engine.registerSymbol(SPECIFICATION));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = Thread.ofPlatform().start(() -> {
                try {
                    engine.orderBook(SYMBOL, 1);
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
            thread.join();
            assertEquals(IllegalStateException.class, failure.get().getClass());
        }
    }

    private static ExchangeConfiguration configuration() {
        return ExchangeConfiguration.defaultBuilder()
                .ordersProcessingCfg(OrdersProcessingConfiguration.builder()
                        .riskProcessingMode(OrdersProcessingConfiguration.RiskProcessingMode.MATCHING_ONLY)
                        .marginTradingMode(OrdersProcessingConfiguration.MarginTradingMode.MARGIN_TRADING_DISABLED)
                        .build())
                .performanceCfg(PerformanceConfiguration.baseBuilder()
                        .matchingEnginesNum(1)
                        .riskEnginesNum(0)
                        .build())
                .build();
    }
}
