package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.dma.DmaLimitOrder;
import exchange.core2.core.common.api.dma.DmaOrderStatus;
import exchange.core2.core.common.api.dma.DmaProtectedMarketOrder;
import exchange.core2.core.common.api.dma.DmaReplaceOrder;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.simulation.EmporiaPortfolioGateway;
import exchange.core2.core.simulation.EmporiaPortfolioSeed;
import exchange.core2.core.simulation.EmporiaPortfolioSnapshot;
import exchange.core2.core.simulation.ProductionSimulation;
import exchange.core2.core.simulation.ProductionSimulationAccounting;
import exchange.core2.core.simulation.ProductionSimulationCheckpoint;
import exchange.core2.core.simulation.ProductionSimulationConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionSimulationAccountingTest {

    private static final int AAPL_USD = 10_001;
    private static final int AAPL_ASSET = 20_001;
    private static final int USD = 840;
    private static final long BUYER = 101;
    private static final long SELLER = 102;
    private static final long UNDERFUNDED_BUYER = 103;

    private static final CoreSymbolSpecification AAPL =
            CoreSymbolSpecification.builder()
                    .symbolId(AAPL_USD)
                    .type(SymbolType.EQUITY)
                    .baseCurrency(AAPL_ASSET)
                    .quoteCurrency(USD)
                    .baseScaleK(1)
                    .quoteScaleK(1)
                    .takerFee(0)
                    .makerFee(0)
                    .build();

    @TempDir
    Path storageDirectory;

    @Test
    @Timeout(30)
    void shouldImportPublishAndRecoverFullyFundedEquityPortfolios()
            throws IOException {
        final RecordingPortfolioGateway gateway =
                new RecordingPortfolioGateway(Map.of(
                        BUYER,
                        seed(BUYER, Map.of(USD, 500L)),
                        SELLER,
                        seed(SELLER, Map.of(AAPL_ASSET, 5L)),
                        UNDERFUNDED_BUYER,
                        seed(UNDERFUNDED_BUYER, Map.of(USD, 499L))));
        final ProductionSimulationAccounting accounting =
                ProductionSimulationAccounting.fullEquityRisk(gateway);
        final ProductionSimulationConfiguration configuration =
                ProductionSimulationConfiguration.create(
                        "full-equity-risk",
                        storageDirectory,
                        2);
        final DmaLimitOrder sell = new DmaLimitOrder(
                11,
                1_001,
                SELLER,
                AAPL_USD,
                OrderAction.ASK,
                100,
                5);
        final DmaProtectedMarketOrder underfundedBuy =
                new DmaProtectedMarketOrder(
                        12,
                        2_001,
                        UNDERFUNDED_BUYER,
                        AAPL_USD,
                        OrderAction.BID,
                        100,
                        5);
        final DmaProtectedMarketOrder buy =
                new DmaProtectedMarketOrder(
                        13,
                        2_002,
                        BUYER,
                        AAPL_USD,
                        OrderAction.BID,
                        100,
                        5);
        final ProductionSimulationCheckpoint checkpoint;

        try (ProductionSimulation simulation =
                     ProductionSimulation.start(configuration, accounting)) {
            simulation.addSymbols(List.of(AAPL));
            simulation.onboardPortfolio(BUYER).join();
            simulation.onboardPortfolio(SELLER).join();
            simulation.onboardPortfolio(UNDERFUNDED_BUYER).join();

            assertEquals(
                    CommandResultCode.SUCCESS,
                    simulation.submit(sell).join()
                            .lifecycleResult()
                            .commandResult()
                            .resultCode());
            assertThrows(
                    CompletionException.class,
                    () -> simulation.replace(new DmaReplaceOrder(
                            14,
                            sell.orderId(),
                            sell.clientId(),
                            sell.symbol(),
                            sell.side(),
                            99,
                            5)).join());

            assertEquals(
                    CommandResultCode.RISK_NSF,
                    simulation.submitProtected(underfundedBuy).join()
                            .lifecycleResult()
                            .commandResult()
                            .resultCode());
            assertEquals(
                    DmaOrderStatus.REJECTED,
                    simulation.getOrder(underfundedBuy.orderId()).status());

            gateway.failOnce(13, BUYER);
            assertThrows(
                    CompletionException.class,
                    () -> simulation.submitProtected(buy).join());
            assertTrue(simulation.submitProtected(buy).join()
                    .lifecycleResult()
                    .duplicateDelivery());
            assertEquals(
                    Map.of(USD, 0L, AAPL_ASSET, 5L),
                    simulation.portfolioSnapshot(BUYER, 13)
                            .join()
                            .availableBalances());
            assertEquals(
                    Map.of(USD, 500L, AAPL_ASSET, 0L),
                    simulation.portfolioSnapshot(SELLER, 13)
                            .join()
                            .availableBalances());
            assertEquals(
                    5,
                    simulation.metrics().rejectedQuantity());
            assertEquals(
                    1,
                    simulation.metrics().fills());
            assertEquals(
                    1,
                    simulation.metrics().portfolioPublicationFailures());
            assertTrue(gateway.wasPublished(13, BUYER));
            assertTrue(gateway.wasPublished(13, SELLER));

            assertEquals(
                    Map.of(USD, 10L, AAPL_ASSET, 5L),
                    simulation.adjustPortfolioBalance(
                                    BUYER,
                                    USD,
                                    10,
                                    2,
                                    20)
                            .join()
                            .availableBalances());
            assertEquals(
                    Map.of(USD, 10L, AAPL_ASSET, 5L),
                    simulation.adjustPortfolioBalance(
                                    BUYER,
                                    USD,
                                    10,
                                    2,
                                    20)
                            .join()
                            .availableBalances());

            checkpoint = simulation.checkpoint(9_002);
        }

        assertThrows(
                IOException.class,
                () -> ProductionSimulation.recover(
                        configuration,
                        checkpoint.checkpointId()));

        try (ProductionSimulation recovered =
                     ProductionSimulation.recover(
                             configuration,
                             checkpoint.checkpointId(),
                             accounting)) {
            assertEquals(
                    Map.of(USD, 10L, AAPL_ASSET, 5L),
                    recovered.portfolioSnapshot(BUYER, 0)
                            .join()
                            .availableBalances());
            assertTrue(recovered.submitProtected(buy).join()
                    .lifecycleResult()
                    .duplicateDelivery());
        }
    }

    private static EmporiaPortfolioSeed seed(
            final long clientId,
            final Map<Integer, Long> balances) {
        return new EmporiaPortfolioSeed(clientId, 1, balances);
    }

    private static final class RecordingPortfolioGateway
            implements EmporiaPortfolioGateway {

        private final Map<Long, EmporiaPortfolioSeed> seeds;
        private final List<EmporiaPortfolioSnapshot> published =
                new ArrayList<>();
        private long failingDeliveryId = -1;
        private long failingClientId = -1;

        private RecordingPortfolioGateway(
                final Map<Long, EmporiaPortfolioSeed> seeds) {
            this.seeds = seeds;
        }

        @Override
        public CompletableFuture<EmporiaPortfolioSeed> load(
                final long clientId) {
            return CompletableFuture.completedFuture(seeds.get(clientId));
        }

        @Override
        public synchronized CompletableFuture<Void> publish(
                final EmporiaPortfolioSnapshot snapshot) {
            if (snapshot.deliveryId() == failingDeliveryId
                    && snapshot.clientId() == failingClientId) {
                failingDeliveryId = -1;
                failingClientId = -1;
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "simulated portfolio outage"));
            }
            if (published.stream().noneMatch(existing ->
                    existing.deliveryId() == snapshot.deliveryId()
                            && existing.clientId() == snapshot.clientId())) {
                published.add(snapshot);
            }
            return CompletableFuture.completedFuture(null);
        }

        private synchronized void failOnce(
                final long deliveryId,
                final long clientId) {
            failingDeliveryId = deliveryId;
            failingClientId = clientId;
        }

        private synchronized boolean wasPublished(
                final long deliveryId,
                final long clientId) {
            return published.stream().anyMatch(snapshot ->
                    snapshot.deliveryId() == deliveryId
                            && snapshot.clientId() == clientId);
        }
    }
}
