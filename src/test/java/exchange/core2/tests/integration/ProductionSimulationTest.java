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
