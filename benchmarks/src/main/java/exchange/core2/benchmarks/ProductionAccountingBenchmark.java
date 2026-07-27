package exchange.core2.benchmarks;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.dma.DmaProtectedMarketOrder;
import exchange.core2.core.simulation.EmporiaPortfolioGateway;
import exchange.core2.core.simulation.EmporiaPortfolioSeed;
import exchange.core2.core.simulation.EmporiaPortfolioSnapshot;
import exchange.core2.core.simulation.ProductionSimulation;
import exchange.core2.core.simulation.ProductionSimulationAccounting;
import exchange.core2.core.simulation.ProductionSimulationConfiguration;
import exchange.core2.core.simulation.ProductionSimulationResult;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = {
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.util=ALL-UNNAMED",
        "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED"
})
public class ProductionAccountingBenchmark {

    private static final long CLIENT_ID = 101;
    private static final int SYMBOL = 60_000;
    private static final int EQUITY_ASSET = 60_001;
    private static final int USD = 840;

    @State(Scope.Benchmark)
    public static class AccountingState {

        private Path storageDirectory;
        private ProductionSimulation simulation;
        private AtomicLong nextId;

        @Setup
        public void setUp() throws IOException {
            storageDirectory =
                    Files.createTempDirectory("exchange-core-accounting-jmh-");
            final EmporiaPortfolioGateway gateway =
                    new InMemoryPortfolioGateway();
            simulation = ProductionSimulation.start(
                    ProductionSimulationConfiguration.create(
                            "jmh-accounting",
                            storageDirectory,
                            1),
                    ProductionSimulationAccounting.fullEquityRisk(gateway));
            simulation.addSymbols(List.of(symbol()));
            simulation.onboardPortfolio(CLIENT_ID).join();
            nextId = new AtomicLong();
        }

        @TearDown
        public void tearDown() throws IOException {
            simulation.close();
            BenchmarkPaths.deleteRecursively(storageDirectory);
        }
    }

    @Benchmark
    public ProductionSimulationResult riskCheckAndPortfolioPublish(
            final AccountingState state) {
        final long id = state.nextId.incrementAndGet();
        return state.simulation.submitProtected(
                        new DmaProtectedMarketOrder(
                                id,
                                id,
                                CLIENT_ID,
                                SYMBOL,
                                OrderAction.BID,
                                100,
                                1))
                .join();
    }

    private static CoreSymbolSpecification symbol() {
        return CoreSymbolSpecification.builder()
                .symbolId(SYMBOL)
                .type(SymbolType.EQUITY)
                .baseCurrency(EQUITY_ASSET)
                .quoteCurrency(USD)
                .baseScaleK(1)
                .quoteScaleK(1)
                .takerFee(0)
                .makerFee(0)
                .build();
    }

    private static final class InMemoryPortfolioGateway
            implements EmporiaPortfolioGateway {

        @Override
        public CompletableFuture<EmporiaPortfolioSeed> load(
                final long clientId) {
            return CompletableFuture.completedFuture(
                    new EmporiaPortfolioSeed(
                            clientId,
                            1,
                            Map.of(USD, 1_000_000_000L)));
        }

        @Override
        public CompletableFuture<Void> publish(
                final EmporiaPortfolioSnapshot snapshot) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
