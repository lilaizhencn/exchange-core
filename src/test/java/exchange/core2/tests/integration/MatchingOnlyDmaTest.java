package exchange.core2.tests.integration;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MatcherResult;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.dma.DmaCancelOrder;
import exchange.core2.core.common.api.dma.DmaFill;
import exchange.core2.core.common.api.dma.DmaLimitOrder;
import exchange.core2.core.common.api.dma.DmaOrderResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.OrdersProcessingConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.orderbook.OrderBookDirectImpl;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchingOnlyDmaTest {

    private static final int AAPL_USD = 10_001;
    private static final int AAPL_ASSET = 20_001;
    private static final int USD = 840;

    private static final CoreSymbolSpecification AAPL = CoreSymbolSpecification.builder()
            .symbolId(AAPL_USD)
            .type(SymbolType.EQUITY)
            .baseCurrency(AAPL_ASSET)
            .quoteCurrency(USD)
            .baseScaleK(1)
            .quoteScaleK(1)
            .takerFee(0)
            .makerFee(0)
            .build();

    private static final OrdersProcessingConfiguration MATCHING_ONLY =
            OrdersProcessingConfiguration.builder()
                    .riskProcessingMode(OrdersProcessingConfiguration.RiskProcessingMode.MATCHING_ONLY)
                    .marginTradingMode(OrdersProcessingConfiguration.MarginTradingMode.MARGIN_TRADING_DISABLED)
                    .build();

    @Test
    @Timeout(15)
    void shouldUseDirectPipelineAndCorrelationWithoutPromiseRegistration() throws Exception {
        final PerformanceConfiguration direct = PerformanceConfiguration.baseBuilder()
                .directMatchingOnlyPipeline(true)
                .matchingEnginesNum(1)
                .riskEnginesNum(1)
                .build();
        try (ExchangeTestContainer container = ExchangeTestContainer.create(direct, MATCHING_ONLY)) {
            container.addSymbol(AAPL);
            AtomicReference<MatcherResult> result = new AtomicReference<>();
            AtomicReference<Long> correlation = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            container.setConsumer((command, sequence) -> {
                if (command.correlationId != 0) {
                    correlation.set(command.correlationId);
                    result.set(MatcherResult.from(sequence, command));
                    completed.countDown();
                }
            });

            container.getApi().submitMatcherPlace(91, System.nanoTime(), 1_001, 0,
                    100, 100, 3, OrderAction.ASK,
                    exchange.core2.core.common.OrderType.GTC, AAPL_USD, 11);

            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(91L, correlation.get());
            assertEquals(CommandResultCode.SUCCESS, result.get().resultCode());
            assertEquals(1_001L, result.get().orderId());
        }
    }

    @Test
    @Timeout(15)
    void shouldSubmitCancelAndProduceDeterministicPriceTimePriorityFills() {
        final ScenarioResult firstNaiveRun = runScenario(PerformanceConfiguration.DEFAULT);
        final ScenarioResult secondNaiveRun = runScenario(PerformanceConfiguration.DEFAULT);
        final ScenarioResult directRun = runScenario(
                PerformanceConfiguration.baseBuilder()
                        .orderBookFactory(OrderBookDirectImpl::new)
                        .build());

        final DmaOrderResult expectedExecution = new DmaOrderResult(
                2_001,
                CommandResultCode.SUCCESS,
                List.of(
                        new DmaFill(1_002, 12, 99, 2, false, true),
                        new DmaFill(1_001, 11, 100, 3, false, true),
                        new DmaFill(1_003, 13, 100, 2, true, false)),
                0,
                0);

        assertEquals(expectedExecution, firstNaiveRun.execution());
        assertEquals(
                new DmaOrderResult(1_003, CommandResultCode.SUCCESS, List.of(), 2, 0),
                firstNaiveRun.cancellation());
        assertEquals(
                new DmaOrderResult(
                        1_003,
                        CommandResultCode.MATCHING_UNKNOWN_ORDER_ID,
                        List.of(),
                        0,
                        0),
                firstNaiveRun.repeatedCancellation());

        assertEquals(firstNaiveRun, secondNaiveRun);
        assertEquals(firstNaiveRun, directRun);
    }

    @Test
    void shouldRejectDmaFlowInLegacyNoRiskMode() {
        final OrdersProcessingConfiguration legacyNoRisk =
                OrdersProcessingConfiguration.builder()
                        .riskProcessingMode(
                                OrdersProcessingConfiguration.RiskProcessingMode
                                        .NO_RISK_PROCESSING)
                        .marginTradingMode(
                                OrdersProcessingConfiguration.MarginTradingMode
                                        .MARGIN_TRADING_DISABLED)
                        .build();

        try (ExchangeTestContainer container =
                     ExchangeTestContainer.create(
                             PerformanceConfiguration.DEFAULT,
                             legacyNoRisk)) {
            final IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> container.getApi().submitDmaLimitOrder(
                            new DmaLimitOrder(1, 10, AAPL_USD, OrderAction.BID, 100, 1)));

            assertTrue(exception.getMessage().contains("FULL_PER_CURRENCY"));
        }
    }

    @Test
    void shouldValidateDmaLimitOrderBeforePublishing() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DmaLimitOrder(1, 10, AAPL_USD, OrderAction.BID, 0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DmaLimitOrder(1, 10, AAPL_USD, OrderAction.BID, 100, 0));
        assertThrows(
                NullPointerException.class,
                () -> new DmaLimitOrder(1, 10, AAPL_USD, null, 100, 1));
    }

    private ScenarioResult runScenario(final PerformanceConfiguration performanceConfiguration) {
        try (ExchangeTestContainer container = ExchangeTestContainer.create(performanceConfiguration, MATCHING_ONLY)) {
            container.addSymbol(AAPL);
            final ExchangeApi api = container.getApi();

            assertResting(api.submitDmaLimitOrder(
                    new DmaLimitOrder(1_001, 11, AAPL_USD, OrderAction.ASK, 100, 3)).join());
            assertResting(api.submitDmaLimitOrder(
                    new DmaLimitOrder(1_002, 12, AAPL_USD, OrderAction.ASK, 99, 2)).join());
            assertResting(api.submitDmaLimitOrder(
                    new DmaLimitOrder(1_003, 13, AAPL_USD, OrderAction.ASK, 100, 4)).join());

            final DmaOrderResult execution = api.submitDmaLimitOrder(
                    new DmaLimitOrder(2_001, 21, AAPL_USD, OrderAction.BID, 100, 7)).join();
            final DmaOrderResult cancellation = api.cancelDmaOrder(
                    new DmaCancelOrder(1_003, 13, AAPL_USD)).join();
            final DmaOrderResult repeatedCancellation = api.cancelDmaOrder(
                    new DmaCancelOrder(1_003, 13, AAPL_USD)).join();

            assertTrue(container.totalBalanceReport().getAccountBalances().isEmpty());

            return new ScenarioResult(execution, cancellation, repeatedCancellation);
        }
    }

    private void assertResting(final DmaOrderResult result) {
        assertEquals(CommandResultCode.SUCCESS, result.resultCode());
        assertTrue(result.fills().isEmpty());
        assertEquals(0, result.cancelledQuantity());
        assertEquals(0, result.rejectedQuantity());
    }

    private record ScenarioResult(
            DmaOrderResult execution,
            DmaOrderResult cancellation,
            DmaOrderResult repeatedCancellation) {
    }
}
