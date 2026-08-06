package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.dma.DmaCancelOrder;
import exchange.core2.core.common.api.dma.DmaLifecycleResult;
import exchange.core2.core.common.api.dma.DmaLifecycleSnapshot;
import exchange.core2.core.common.api.dma.DmaOrderResult;
import exchange.core2.core.common.cmd.CommandResultCode;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

            // Closing it: a caller that persists order state elsewhere rebuilds
            // the projection and applies it, after which the lifecycle resolves
            // the replayed order. This is what execution-service does from
            // order-management on startup.
            recovered.recoverLifecycle(rebuiltLifecycle(beforeSnapshot, afterSnapshot));

            assertOrder(
                    recovered.getOrder(afterSnapshot.orderId()),
                    DmaOrderStatus.LIVE,
                    0,
                    7);
            assertOrder(
                    recovered.getOrder(beforeSnapshot.orderId()),
                    DmaOrderStatus.LIVE,
                    0,
                    10);

            // And the rebuilt deliveries still deduplicate, so a command
            // redelivered after the crash is recognised rather than executed
            // a second time.
            assertTrue(recovered.submit(afterSnapshot).join()
                    .lifecycleResult()
                    .duplicateDelivery());
        }
    }

    /**
     * A recovered exchange must be able to keep journalling.
     *
     * <p>It could not: journal files are named
     * {@code <exchange>_journal_<snapshotId>_<counter>}, and the counter
     * restarted at zero on every startup, so the first command after a recovery
     * tried to create the file it had just replayed and died with "File already
     * exists". The exchange came up, then silently accepted nothing.
     *
     * <p>Recovering twice is the point: one recovery only proves replay works,
     * and the failure is in what happens <em>after</em> it.
     */
    @Test
    @Timeout(30)
    void shouldKeepJournallingAfterRecovery() throws IOException {
        final ProductionSimulationConfiguration configuration =
                ProductionSimulationConfiguration.create(
                        "twice-recovered", storageDirectory, 2, true);

        final DmaLimitOrder first = limit(301, 4_001, 41, AAPL_USD, 100, 3);
        final DmaLimitOrder second = limit(302, 4_002, 42, AAPL_USD, 101, 4);
        final DmaLimitOrder third = limit(303, 4_003, 43, AAPL_USD, 102, 5);
        final ProductionSimulationCheckpoint checkpoint;

        try (ProductionSimulation simulation =
                     ProductionSimulation.start(configuration)) {
            simulation.addSymbols(List.of(AAPL, MSFT));
            simulation.submit(first).join();
            checkpoint = simulation.checkpoint(9_201);
            simulation.submit(second).join();
        }

        // First recovery, then write again - this is what used to throw
        // "File already exists" and take the whole journal down.
        try (ProductionSimulation recovered =
                     ProductionSimulation.recover(
                             configuration, checkpoint.checkpointId())) {
            assertEquals(2, recovered.orderBook(AAPL_USD).askSize);
            recovered.submit(third).join();
            assertEquals(3, recovered.orderBook(AAPL_USD).askSize);
        }

        // A second recovery sees all three: a recovered exchange keeps
        // journalling. The boundary suppressing re-journalling during replay is
        // now the count of commands actually replayed, in this process's
        // sequence space, rather than the sequence the previous process
        // recorded.
        try (ProductionSimulation again =
                     ProductionSimulation.recover(
                             configuration, checkpoint.checkpointId())) {
            assertEquals(3, again.orderBook(AAPL_USD).askSize);
        }
    }

    /**
     * Stands in for the projection execution-service rebuilds from
     * order-management, which is the durable record of these orders.
     */
    private static DmaLifecycleSnapshot rebuiltLifecycle(final DmaLimitOrder... orders) {
        final Map<Long, DmaOrderState> states = new HashMap<>();
        final List<DmaLifecycleSnapshot.CompletedDelivery> deliveries = new ArrayList<>();
        for (final DmaLimitOrder order : orders) {
            final DmaOrderState state = new DmaOrderState(
                    order, DmaOrderStatus.LIVE, 0, 0, 0, order.quantity(), 1);
            states.put(order.orderId(), state);
            deliveries.add(new DmaLifecycleSnapshot.CompletedDelivery(
                    order,
                    new DmaLifecycleResult(
                            order.deliveryId(),
                            new DmaOrderResult(
                                    order.orderId(), CommandResultCode.SUCCESS, List.of(), 0, 0),
                            state,
                            false)));
        }
        return new DmaLifecycleSnapshot(states, deliveries);
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
