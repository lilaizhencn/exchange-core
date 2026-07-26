package exchange.core2.tests.integration;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.dma.DmaCancelOrder;
import exchange.core2.core.common.api.dma.DmaLifecycleResult;
import exchange.core2.core.common.api.dma.DmaLifecycleSnapshot;
import exchange.core2.core.common.api.dma.DmaLimitOrder;
import exchange.core2.core.common.api.dma.DmaOrderState;
import exchange.core2.core.common.api.dma.DmaOrderStatus;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.common.config.OrdersProcessingConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.dma.DmaOrderLifecycleService;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DmaOrderLifecycleTest {

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
    void shouldTrackPartialFillsAndDeduplicateCompletedDeliveries() {
        try (ExchangeTestContainer container = matchingOnlyContainer()) {
            final AtomicInteger placeCommands = new AtomicInteger();
            container.setConsumer((command, sequence) -> {
                if (command.command == OrderCommandType.PLACE_ORDER) {
                    placeCommands.incrementAndGet();
                }
            });

            final DmaOrderLifecycleService lifecycle = container.getApi().dmaLifecycle();
            final DmaLimitOrder maker = ask(101, 1_001, 11, 10);
            final DmaLimitOrder taker = bid(102, 2_001, 21, 4);

            assertEquals(DmaOrderStatus.LIVE, lifecycle.submit(maker).join().orderState().status());
            final DmaLifecycleResult takerResult = lifecycle.submit(taker).join();

            assertEquals(DmaOrderStatus.FILLED, takerResult.orderState().status());
            assertOrder(lifecycle.getOrder(maker.orderId()), DmaOrderStatus.PARTIALLY_FILLED, 4, 0, 6);

            final DmaLifecycleResult duplicate = lifecycle.submit(taker).join();

            assertTrue(duplicate.duplicateDelivery());
            assertEquals(takerResult.commandResult(), duplicate.commandResult());
            assertOrder(lifecycle.getOrder(maker.orderId()), DmaOrderStatus.PARTIALLY_FILLED, 4, 0, 6);
            assertEquals(2, placeCommands.get());

            assertThrows(
                    IllegalArgumentException.class,
                    () -> lifecycle.submit(bid(102, 2_001, 21, 5)));
            assertEquals(2, placeCommands.get());
        }
    }

    @Test
    @Timeout(15)
    void shouldDeduplicateInFlightSubmitAndQueueCancellationUntilSubmitCompletes() throws Exception {
        try (ExchangeTestContainer container = matchingOnlyContainer()) {
            final CountDownLatch submitResultObserved = new CountDownLatch(1);
            final CountDownLatch releaseSubmitResult = new CountDownLatch(1);
            final AtomicInteger placeCommands = new AtomicInteger();
            final AtomicInteger cancelCommands = new AtomicInteger();

            container.setConsumer((command, sequence) -> {
                if (command.command == OrderCommandType.PLACE_ORDER) {
                    placeCommands.incrementAndGet();
                }
                if (command.command == OrderCommandType.CANCEL_ORDER) {
                    cancelCommands.incrementAndGet();
                }
                if (command.command == OrderCommandType.PLACE_ORDER && command.orderId == 1_001) {
                    submitResultObserved.countDown();
                    try {
                        if (!releaseSubmitResult.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("timed out waiting to release submit result");
                        }
                    } catch (final InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("submit result wait was interrupted", interrupted);
                    }
                }
            });

            final DmaOrderLifecycleService lifecycle = container.getApi().dmaLifecycle();
            final DmaLimitOrder maker = ask(201, 1_001, 11, 10);
            final CompletableFuture<DmaLifecycleResult> originalSubmit = lifecycle.submit(maker);

            assertTrue(submitResultObserved.await(5, TimeUnit.SECONDS));
            final CompletableFuture<DmaLifecycleResult> duplicateSubmit = lifecycle.submit(maker);
            final CompletableFuture<DmaLifecycleResult> queuedCancel =
                    lifecycle.cancel(new DmaCancelOrder(202, maker.orderId(), maker.clientId(), maker.symbol()));

            assertFalse(originalSubmit.isDone());
            assertFalse(duplicateSubmit.isDone());
            assertFalse(queuedCancel.isDone());
            assertEquals(DmaOrderStatus.NEW, lifecycle.getOrder(maker.orderId()).status());
            assertThrows(IllegalStateException.class, lifecycle::snapshot);

            releaseSubmitResult.countDown();

            assertFalse(originalSubmit.get(5, TimeUnit.SECONDS).duplicateDelivery());
            assertTrue(duplicateSubmit.get(5, TimeUnit.SECONDS).duplicateDelivery());
            assertOrder(
                    queuedCancel.get(5, TimeUnit.SECONDS).orderState(),
                    DmaOrderStatus.CANCELLED,
                    0,
                    10,
                    0);
            assertEquals(1, placeCommands.get());
            assertEquals(1, cancelCommands.get());
        }
    }

    @Test
    @Timeout(15)
    void shouldLinearizeCancellationAgainstExecutionWithoutLosingQuantity() throws Exception {
        try (ExchangeTestContainer container = matchingOnlyContainer()) {
            final DmaOrderLifecycleService lifecycle = container.getApi().dmaLifecycle();
            final DmaLimitOrder maker = ask(301, 1_001, 11, 10);

            lifecycle.submit(maker).join();
            lifecycle.submit(bid(302, 2_001, 21, 4)).join();
            assertOrder(lifecycle.getOrder(maker.orderId()), DmaOrderStatus.PARTIALLY_FILLED, 4, 0, 6);

            final CountDownLatch start = new CountDownLatch(1);
            final ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                final CompletableFuture<DmaLifecycleResult> cancellation =
                        CompletableFuture.supplyAsync(
                                () -> {
                                    await(start);
                                    return lifecycle.cancel(
                                            new DmaCancelOrder(
                                                    303,
                                                    maker.orderId(),
                                                    maker.clientId(),
                                                    maker.symbol())).join();
                                },
                                executor);
                final CompletableFuture<DmaLifecycleResult> execution =
                        CompletableFuture.supplyAsync(
                                () -> {
                                    await(start);
                                    return lifecycle.submit(bid(304, 2_002, 22, 6)).join();
                                },
                                executor);

                start.countDown();
                cancellation.get(5, TimeUnit.SECONDS);
                final DmaLifecycleResult executionResult = execution.get(5, TimeUnit.SECONDS);
                final DmaOrderState makerState = lifecycle.getOrder(maker.orderId());

                assertTrue(makerState.status() == DmaOrderStatus.FILLED
                        || makerState.status() == DmaOrderStatus.CANCELLED);
                assertEquals(10, makerState.filledQuantity() + makerState.cancelledQuantity());
                assertEquals(0, makerState.remainingQuantity());
                assertEquals(0, makerState.rejectedQuantity());

                if (makerState.status() == DmaOrderStatus.FILLED) {
                    assertEquals(10, makerState.filledQuantity());
                    assertEquals(0, makerState.cancelledQuantity());
                    assertEquals(DmaOrderStatus.FILLED, executionResult.orderState().status());
                } else {
                    assertEquals(4, makerState.filledQuantity());
                    assertEquals(6, makerState.cancelledQuantity());
                    assertEquals(DmaOrderStatus.LIVE, executionResult.orderState().status());
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    @Timeout(15)
    void shouldRecoverLifecycleAndDeliveryDeduplicationFromSnapshot() {
        try (ExchangeTestContainer container = matchingOnlyContainer()) {
            final AtomicInteger placeCommands = new AtomicInteger();
            final AtomicInteger cancelCommands = new AtomicInteger();
            container.setConsumer((command, sequence) -> {
                if (command.command == OrderCommandType.PLACE_ORDER) {
                    placeCommands.incrementAndGet();
                } else if (command.command == OrderCommandType.CANCEL_ORDER) {
                    cancelCommands.incrementAndGet();
                }
            });

            final ExchangeApi api = container.getApi();
            DmaOrderLifecycleService lifecycle = api.dmaLifecycle();
            final DmaLimitOrder maker = ask(401, 1_001, 11, 10);
            final DmaLimitOrder taker = bid(402, 2_001, 21, 4);

            lifecycle.submit(maker).join();
            lifecycle.submit(taker).join();
            final DmaLifecycleSnapshot snapshot = lifecycle.snapshot();

            lifecycle = api.recoverDmaLifecycle(snapshot);
            assertOrder(lifecycle.getOrder(maker.orderId()), DmaOrderStatus.PARTIALLY_FILLED, 4, 0, 6);

            assertTrue(lifecycle.submit(taker).join().duplicateDelivery());
            assertOrder(lifecycle.getOrder(maker.orderId()), DmaOrderStatus.PARTIALLY_FILLED, 4, 0, 6);
            assertEquals(2, placeCommands.get());

            final DmaCancelOrder cancel =
                    new DmaCancelOrder(403, maker.orderId(), maker.clientId(), maker.symbol());
            assertOrder(
                    lifecycle.cancel(cancel).join().orderState(),
                    DmaOrderStatus.CANCELLED,
                    4,
                    6,
                    0);

            lifecycle = api.recoverDmaLifecycle(lifecycle.snapshot());
            assertTrue(lifecycle.cancel(cancel).join().duplicateDelivery());
            assertOrder(lifecycle.getOrder(maker.orderId()), DmaOrderStatus.CANCELLED, 4, 6, 0);
            assertEquals(1, cancelCommands.get());
        }
    }

    private static ExchangeTestContainer matchingOnlyContainer() {
        final ExchangeTestContainer container =
                ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT, MATCHING_ONLY);
        container.addSymbol(AAPL);
        return container;
    }

    private static DmaLimitOrder ask(
            final long deliveryId,
            final long orderId,
            final long clientId,
            final long quantity) {
        return new DmaLimitOrder(
                deliveryId,
                orderId,
                clientId,
                AAPL_USD,
                OrderAction.ASK,
                100,
                quantity);
    }

    private static DmaLimitOrder bid(
            final long deliveryId,
            final long orderId,
            final long clientId,
            final long quantity) {
        return new DmaLimitOrder(
                deliveryId,
                orderId,
                clientId,
                AAPL_USD,
                OrderAction.BID,
                100,
                quantity);
    }

    private static void assertOrder(
            final DmaOrderState state,
            final DmaOrderStatus status,
            final long filled,
            final long cancelled,
            final long remaining) {
        assertEquals(status, state.status());
        assertEquals(filled, state.filledQuantity());
        assertEquals(cancelled, state.cancelledQuantity());
        assertEquals(remaining, state.remainingQuantity());
        assertEquals(state.order().quantity(), filled + cancelled + state.rejectedQuantity() + remaining);
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to start lifecycle race");
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("lifecycle race was interrupted", interrupted);
        }
    }
}
