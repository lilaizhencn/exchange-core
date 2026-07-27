package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.dma.DmaFill;
import exchange.core2.core.common.api.dma.DmaLifecycleResult;
import exchange.core2.core.common.api.dma.DmaLimitOrder;
import exchange.core2.core.common.api.dma.DmaOrderResult;
import exchange.core2.core.common.api.dma.DmaOrderState;
import exchange.core2.core.common.api.dma.DmaOrderStatus;
import exchange.core2.core.common.api.dma.DmaProtectedMarketOrder;
import exchange.core2.core.common.api.dma.DmaReplaceOrder;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.common.config.OrdersProcessingConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.dma.DmaOrderLifecycleService;
import exchange.core2.core.orderbook.OrderBookDirectImpl;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DmaAdvancedOrdersTest {

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
    void shouldApplyAtomicPriceAndTotalQuantityReplacement() {
        final ReplaceScenario naive = runReplaceScenario(PerformanceConfiguration.DEFAULT);
        final ReplaceScenario direct = runReplaceScenario(
                PerformanceConfiguration.baseBuilder()
                        .orderBookFactory(OrderBookDirectImpl::new)
                        .build());

        assertEquals(naive, direct);
        assertEquals(CommandResultCode.SUCCESS, naive.replacement().commandResult().resultCode());
        assertEquals(
                List.of(new DmaFill(2_001, 21, 104, 3, false, true)),
                naive.replacement().commandResult().fills());
        assertOrder(naive.replacement().orderState(), DmaOrderStatus.PARTIALLY_FILLED, 104, 8, 7, 1);

        assertEquals(
                CommandResultCode.MATCHING_REPLACE_FAILED_INVALID_QUANTITY,
                naive.staleReplacement().commandResult().resultCode());
        assertOrder(naive.staleReplacement().orderState(), DmaOrderStatus.PARTIALLY_FILLED, 104, 8, 7, 1);

        assertTrue(naive.duplicateReplacement().duplicateDelivery());
        assertOrder(naive.expandedReplacement().orderState(), DmaOrderStatus.PARTIALLY_FILLED, 103, 10, 7, 3);
        assertEquals(3, naive.replaceCommands());
        assertEquals(1, naive.orderBook().askSize);
        assertEquals(103, naive.orderBook().askPrices[0]);
        assertEquals(3, naive.orderBook().askVolumes[0]);
        assertEquals(0, naive.orderBook().bidSize);
    }

    @Test
    @Timeout(15)
    void shouldExecuteProtectedMarketIocOnlyInsideItsPriceBoundary() {
        try (ExchangeTestContainer container = matchingOnlyContainer(PerformanceConfiguration.DEFAULT)) {
            final AtomicInteger placeCommands = new AtomicInteger();
            container.setConsumer((command, sequence) -> {
                if (command.command == OrderCommandType.PLACE_ORDER) {
                    placeCommands.incrementAndGet();
                }
            });

            final DmaOrderLifecycleService lifecycle = container.getApi().dmaLifecycle();
            lifecycle.submit(limit(501, 1_001, 11, OrderAction.ASK, 99, 2)).join();
            lifecycle.submit(limit(502, 1_002, 12, OrderAction.ASK, 101, 3)).join();
            lifecycle.submit(limit(503, 1_003, 13, OrderAction.ASK, 103, 4)).join();

            final DmaProtectedMarketOrder protectedBid = new DmaProtectedMarketOrder(
                    504,
                    2_001,
                    21,
                    AAPL_USD,
                    OrderAction.BID,
                    101,
                    10);
            final DmaLifecycleResult result = lifecycle.submitProtected(protectedBid).join();

            assertEquals(CommandResultCode.SUCCESS, result.commandResult().resultCode());
            assertEquals(
                    List.of(
                            new DmaFill(1_001, 11, 99, 2, false, true),
                            new DmaFill(1_002, 12, 101, 3, false, true)),
                    result.commandResult().fills());
            assertOrder(result.orderState(), DmaOrderStatus.REJECTED, 101, 10, 5, 0);
            assertEquals(5, result.orderState().rejectedQuantity());
            assertTrue(result.commandResult().fills().stream()
                    .allMatch(fill -> fill.price() <= protectedBid.protectionPrice()));

            final DmaLifecycleResult duplicate = lifecycle.submitProtected(protectedBid).join();
            assertTrue(duplicate.duplicateDelivery());
            assertEquals(4, placeCommands.get());

            final L2MarketData orderBook = container.requestCurrentOrderBook(AAPL_USD);
            assertEquals(1, orderBook.askSize);
            assertEquals(103, orderBook.askPrices[0]);
            assertEquals(4, orderBook.askVolumes[0]);
            assertEquals(0, orderBook.bidSize);
        }
    }

    @Test
    void shouldAllowProtectedOrdersButRejectAtomicReplaceWithFullRisk() {
        try (ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addSymbol(AAPL);
            final DmaOrderResult protectedResult =
                    container.getApi().submitDmaProtectedMarketOrder(
                            new DmaProtectedMarketOrder(
                                    601,
                                    1_001,
                                    11,
                                    AAPL_USD,
                                    OrderAction.BID,
                                    100,
                                    1))
                            .join();
            assertEquals(
                    CommandResultCode.AUTH_INVALID_USER,
                    protectedResult.resultCode());

            assertThrows(
                    IllegalStateException.class,
                    () -> container.getApi().replaceDmaOrder(
                            new DmaReplaceOrder(
                                    602,
                                    1_001,
                                    11,
                                    AAPL_USD,
                                    OrderAction.BID,
                                    100,
                                    1)));
        }
    }

    private static ReplaceScenario runReplaceScenario(
            final PerformanceConfiguration performanceConfiguration) {
        try (ExchangeTestContainer container = matchingOnlyContainer(performanceConfiguration)) {
            final AtomicInteger replaceCommands = new AtomicInteger();
            container.setConsumer((command, sequence) -> {
                if (command.command == OrderCommandType.REPLACE_ORDER) {
                    replaceCommands.incrementAndGet();
                }
            });

            DmaOrderLifecycleService lifecycle = container.getApi().dmaLifecycle();
            final DmaLimitOrder order =
                    limit(101, 1_001, 11, OrderAction.ASK, 105, 10);
            lifecycle.submit(order).join();
            lifecycle.submit(limit(102, 2_000, 20, OrderAction.BID, 105, 4)).join();
            lifecycle.submit(limit(103, 2_001, 21, OrderAction.BID, 104, 3)).join();

            final DmaReplaceOrder replacement = new DmaReplaceOrder(
                    104,
                    order.orderId(),
                    order.clientId(),
                    order.symbol(),
                    order.side(),
                    104,
                    8);
            final DmaLifecycleResult replacementResult = lifecycle.replace(replacement).join();
            lifecycle = container.getApi().recoverDmaLifecycle(lifecycle.snapshot());
            final DmaLifecycleResult duplicateReplacement = lifecycle.replace(replacement).join();
            final DmaLifecycleResult staleReplacement = lifecycle.replace(
                    new DmaReplaceOrder(
                            105,
                            order.orderId(),
                            order.clientId(),
                            order.symbol(),
                            order.side(),
                            103,
                            7)).join();
            final DmaLifecycleResult expandedReplacement = lifecycle.replace(
                    new DmaReplaceOrder(
                            106,
                            order.orderId(),
                            order.clientId(),
                            order.symbol(),
                            order.side(),
                            103,
                            10)).join();

            return new ReplaceScenario(
                    replacementResult,
                    staleReplacement,
                    duplicateReplacement,
                    expandedReplacement,
                    replaceCommands.get(),
                    container.requestCurrentOrderBook(AAPL_USD));
        }
    }

    private static ExchangeTestContainer matchingOnlyContainer(
            final PerformanceConfiguration performanceConfiguration) {
        final ExchangeTestContainer container =
                ExchangeTestContainer.create(performanceConfiguration, MATCHING_ONLY);
        container.addSymbol(AAPL);
        return container;
    }

    private static DmaLimitOrder limit(
            final long deliveryId,
            final long orderId,
            final long clientId,
            final OrderAction side,
            final long price,
            final long quantity) {
        return new DmaLimitOrder(
                deliveryId,
                orderId,
                clientId,
                AAPL_USD,
                side,
                price,
                quantity);
    }

    private static void assertOrder(
            final DmaOrderState state,
            final DmaOrderStatus status,
            final long price,
            final long quantity,
            final long filled,
            final long remaining) {
        assertEquals(status, state.status());
        assertEquals(price, state.order().price());
        assertEquals(quantity, state.order().quantity());
        assertEquals(filled, state.filledQuantity());
        assertEquals(remaining, state.remainingQuantity());
        assertEquals(
                quantity,
                state.filledQuantity()
                        + state.cancelledQuantity()
                        + state.rejectedQuantity()
                        + state.remainingQuantity());
    }

    private record ReplaceScenario(
            DmaLifecycleResult replacement,
            DmaLifecycleResult staleReplacement,
            DmaLifecycleResult duplicateReplacement,
            DmaLifecycleResult expandedReplacement,
            int replaceCommands,
            L2MarketData orderBook) {
    }
}
