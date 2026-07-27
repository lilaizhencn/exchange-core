package exchange.core2.benchmarks;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.dma.DmaLimitOrder;
import exchange.core2.core.simulation.ProductionSimulation;
import exchange.core2.core.simulation.ProductionSimulationCheckpoint;
import exchange.core2.core.simulation.ProductionSimulationConfiguration;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
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

@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
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
public class ProductionCheckpointBenchmark {

    private static final int SYMBOL = 40_000;

    @State(Scope.Thread)
    public static class CheckpointState {

        @Param({"100", "1000"})
        public int lifecycleOrders;

        private Path storageDirectory;
        private ProductionSimulation simulation;

        @Setup(Level.Invocation)
        public void setUp() throws IOException {
            storageDirectory = Files.createTempDirectory(
                    "exchange-core-checkpoint-jmh-");
            simulation = ProductionSimulation.start(
                    ProductionSimulationConfiguration.create(
                            "jmh-checkpoint",
                            storageDirectory,
                            4));
            simulation.addSymbols(List.of(symbol()));
            for (int index = 1; index <= lifecycleOrders; index++) {
                simulation.submit(new DmaLimitOrder(
                                index,
                                index,
                                1,
                                SYMBOL,
                                OrderAction.ASK,
                                100 + index % 10,
                                1))
                        .join();
            }
        }

        @TearDown(Level.Invocation)
        public void tearDown() throws IOException {
            simulation.close();
            BenchmarkPaths.deleteRecursively(storageDirectory);
        }
    }

    @Benchmark
    public ProductionSimulationCheckpoint checkpoint(
            final CheckpointState state) throws IOException {
        return state.simulation.checkpoint(1);
    }

    private static CoreSymbolSpecification symbol() {
        return CoreSymbolSpecification.builder()
                .symbolId(SYMBOL)
                .type(SymbolType.EQUITY)
                .baseCurrency(50_000)
                .quoteCurrency(840)
                .baseScaleK(1)
                .quoteScaleK(1)
                .takerFee(0)
                .makerFee(0)
                .build();
    }
}
