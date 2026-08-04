package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.dma.DmaCancelOrder;
import exchange.core2.core.common.api.dma.DmaLimitOrder;
import exchange.core2.core.common.api.dma.DmaOrderState;
import exchange.core2.core.common.api.dma.DmaOrderStatus;
import exchange.core2.core.common.api.dma.DmaProtectedMarketOrder;
import exchange.core2.core.simulation.ProductionSimulation;
import exchange.core2.core.simulation.ProductionSimulationCheckpoint;
import exchange.core2.core.simulation.ProductionSimulationConfiguration;
import exchange.core2.core.simulation.ProductionSimulationMetrics;
import exchange.core2.core.simulation.ProductionSimulationResult;
import exchange.core2.core.simulation.SimulationOperation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionSimulationTest {

    private static final int AAPL_USD = 10_001;
    private static final int MSFT_USD = 10_002;
    private static final int USD = 840;

    private static final CoreSymbolSpecification AAPL =
            equity(AAPL_USD, 20_001);
    private static final CoreSymbolSpecification MSFT =
            equity(MSFT_USD, 20_002);

    @TempDir
    Path storageDirectory;

    @Test
    @Timeout(30)
    void shouldCheckpointRecoverMetricsAndSymbolPartitionOrder()
            throws IOException {
        final ProductionSimulationConfiguration configuration =
                ProductionSimulationConfiguration.create(
                        "production-simulation",
                        storageDirectory,
                        2);
        final DmaLimitOrder aaplAsk =
                limit(101, 1_001, 11, AAPL_USD, 100, 10);
        final DmaProtectedMarketOrder aaplBuy =
                new DmaProtectedMarketOrder(
                        102,
                        2_001,
                        21,
                        AAPL_USD,
                        OrderAction.BID,
                        100,
                        4);
        final ProductionSimulationCheckpoint checkpoint;

        try (ProductionSimulation simulation =
                     ProductionSimulation.start(configuration)) {
            simulation.addSymbols(List.of(AAPL, MSFT));

            final ProductionSimulationResult askResult =
                    simulation.submit(aaplAsk).join();
            final ProductionSimulationResult buyResult =
                    simulation.submitProtected(aaplBuy).join();
            final ProductionSimulationResult msftResult =
                    simulation.submit(
                            limit(103, 1_002, 12, MSFT_USD, 200, 5))
                            .join();
            final ProductionSimulationResult duplicateBuy =
                    simulation.submitProtected(aaplBuy).join();

            assertEquals(AAPL_USD & 1, askResult.partition());
            assertEquals(askResult.partition(), buyResult.partition());
            assertEquals(
                    askResult.partitionSequence() + 1,
                    buyResult.partitionSequence());
            assertEquals(1, msftResult.partitionSequence());
            assertTrue(duplicateBuy.lifecycleResult().duplicateDelivery());
            assertOrder(
                    simulation.getOrder(aaplAsk.orderId()),
                    DmaOrderStatus.PARTIALLY_FILLED,
                    4,
                    6);

            final ProductionSimulationMetrics.Snapshot metrics =
                    simulation.metrics();
            assertEquals(4, metrics.submitted());
            assertEquals(4, metrics.succeeded());
            assertEquals(1, metrics.duplicateDeliveries());
            assertEquals(1, metrics.fills());
            assertEquals(4, metrics.filledQuantity());
            assertTrue(metrics.operations()
                    .get(SimulationOperation.SUBMIT_PROTECTED)
                    .latencyP99Nanos() > 0);

            checkpoint = simulation.checkpoint(9_001);
            assertTrue(Files.isRegularFile(checkpoint.lifecyclePath()));
            assertEquals(
                    1,
                    simulation.metrics()
                            .operations()
                            .get(SimulationOperation.CHECKPOINT)
                            .succeeded());
        }

        try (ProductionSimulation recovered =
                     ProductionSimulation.recover(
                             configuration,
                             checkpoint.checkpointId())) {
            assertOrder(
                    recovered.getOrder(aaplAsk.orderId()),
                    DmaOrderStatus.PARTIALLY_FILLED,
                    4,
                    6);
            assertTrue(recovered.submitProtected(aaplBuy).join()
                    .lifecycleResult()
                    .duplicateDelivery());

            recovered.cancel(new DmaCancelOrder(
                    104,
                    aaplAsk.orderId(),
                    aaplAsk.clientId(),
                    aaplAsk.symbol())).join();
            assertOrder(
                    recovered.getOrder(aaplAsk.orderId()),
                    DmaOrderStatus.CANCELLED,
                    4,
                    0);

            assertEquals(0, recovered.orderBook(AAPL_USD).askSize);
            assertEquals(1, recovered.orderBook(MSFT_USD).askSize);
            assertEquals(1, recovered.metrics().duplicateDeliveries());
            assertEquals(0, recovered.metrics().fills());
        }
    }

    /**
     * The durability guarantee behind moving checkpointing off the command path.
     *
     * <p>With a per-command snapshot, "recovered" and "snapshotted" meant the
     * same thing. With a periodic snapshot they do not: everything accepted
     * after the last snapshot exists only in the journal. This asserts that
     * window is actually replayed, which is the whole basis for not
     * snapshotting per order.
     */
    @Test
    @Timeout(30)
    void shouldReplayCommandsJournalledAfterTheLastSnapshot() throws IOException {
        final ProductionSimulationConfiguration configuration =
                ProductionSimulationConfiguration.create(
                        "journalled-simulation",
                        storageDirectory,
                        2,
                        true);

        final DmaLimitOrder beforeSnapshot = limit(201, 3_001, 31, AAPL_USD, 100, 10);
        final DmaLimitOrder afterSnapshot = limit(202, 3_002, 32, AAPL_USD, 105, 7);
        final ProductionSimulationCheckpoint checkpoint;

        try (ProductionSimulation simulation =
                     ProductionSimulation.start(configuration)) {
            simulation.addSymbols(List.of(AAPL, MSFT));
            simulation.submit(beforeSnapshot).join();

            checkpoint = simulation.checkpoint(9_101);

            // Accepted after the snapshot, so it survives only if the journal
            // is written and replayed.
            simulation.submit(afterSnapshot).join();
            assertOrder(
                    simulation.getOrder(afterSnapshot.orderId()),
                    DmaOrderStatus.LIVE,
                    0,
                    7);
        }

        try (ProductionSimulation recovered =
                     ProductionSimulation.recover(
                             configuration,
                             checkpoint.checkpointId())) {
            // The snapshot covers everything up to the checkpoint.
            assertOrder(
                    recovered.getOrder(beforeSnapshot.orderId()),
                    DmaOrderStatus.LIVE,
                    0,
                    10);

            // The matching engine replays the post-snapshot command from the
            // journal, so the book is whole.
            assertEquals(2, recovered.orderBook(AAPL_USD).askSize);

            // ...but the DMA lifecycle does not. DmaOrderLifecycleService keeps
            // plain HashMaps on the API side, populated when commands are
            // submitted through the API; journal replay drives the disruptor,
            // not the API, so nothing repopulates them. Its only durability is
            // the per-checkpoint .dmas snapshot.
            //
            // This is the gap that stops a periodic snapshot being a drop-in
            // replacement for a per-order one: the engine and the lifecycle
            // view diverge for everything accepted since the last snapshot.
            // Documented in rework/WAL_LIFECYCLE_GAP.md.
            assertThrows(
                    IllegalArgumentException.class,
                    () -> recovered.getOrder(afterSnapshot.orderId()));
        }
    }

    private static CoreSymbolSpecification equity(
            final int symbol,
            final int asset) {
        return CoreSymbolSpecification.builder()
                .symbolId(symbol)
                .type(SymbolType.EQUITY)
                .baseCurrency(asset)
                .quoteCurrency(USD)
                .baseScaleK(1)
                .quoteScaleK(1)
                .takerFee(0)
                .makerFee(0)
                .build();
    }

    private static DmaLimitOrder limit(
            final long deliveryId,
            final long orderId,
            final long clientId,
            final int symbol,
            final long price,
            final long quantity) {
        return new DmaLimitOrder(
                deliveryId,
                orderId,
                clientId,
                symbol,
                OrderAction.ASK,
                price,
                quantity);
    }

    private static void assertOrder(
            final DmaOrderState state,
            final DmaOrderStatus status,
            final long filled,
            final long remaining) {
        assertEquals(status, state.status());
        assertEquals(filled, state.filledQuantity());
        assertEquals(remaining, state.remainingQuantity());
    }
}
