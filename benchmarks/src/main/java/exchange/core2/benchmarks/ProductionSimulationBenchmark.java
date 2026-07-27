package exchange.core2.benchmarks;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.dma.DmaProtectedMarketOrder;
import exchange.core2.core.simulation.ProductionSimulation;
import exchange.core2.core.simulation.ProductionSimulationConfiguration;
import exchange.core2.core.simulation.ProductionSimulationResult;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
public class ProductionSimulationBenchmark {

    private static final int FIRST_SYMBOL = 20_000;
    private static final int SYMBOL_COUNT = 16;

    @State(Scope.Benchmark)
    public static class SimulationState {

        @Param({"1", "4"})
        public int partitions;

        private Path storageDirectory;
        private ProductionSimulation simulation;
        private AtomicLong nextId;

        @Setup
        public void setUp() throws IOException {
            storageDirectory =
                    Files.createTempDirectory("exchange-core-jmh-");
            simulation = ProductionSimulation.start(
                    ProductionSimulationConfiguration.create(
                            "jmh-live",
                            storageDirectory,
                            partitions));
            simulation.addSymbols(symbols());
            nextId = new AtomicLong();
        }

        @TearDown
        public void tearDown() throws IOException {
            simulation.close();
            BenchmarkPaths.deleteRecursively(storageDirectory);
        }
    }

    @Benchmark
    public ProductionSimulationResult protectedIocRoundTrip(
            final SimulationState state) {
        final long id = state.nextId.incrementAndGet();
        final int symbol =
                FIRST_SYMBOL + (int) (id & (SYMBOL_COUNT - 1));
        return state.simulation.submitProtected(
                        new DmaProtectedMarketOrder(
                                id,
                                id,
                                1,
                                symbol,
                                OrderAction.BID,
                                100,
                                1))
                .join();
    }

    private static List<CoreSymbolSpecification> symbols() {
        return java.util.stream.IntStream.range(0, SYMBOL_COUNT)
                .mapToObj(index -> CoreSymbolSpecification.builder()
                        .symbolId(FIRST_SYMBOL + index)
                        .type(SymbolType.EQUITY)
                        .baseCurrency(30_000 + index)
                        .quoteCurrency(840)
                        .baseScaleK(1)
                        .quoteScaleK(1)
                        .takerFee(0)
                        .makerFee(0)
                        .build())
                .toList();
    }
}
