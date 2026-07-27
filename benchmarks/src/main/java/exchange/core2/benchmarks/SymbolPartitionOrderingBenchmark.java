package exchange.core2.benchmarks;

import exchange.core2.core.simulation.SymbolPartitionDispatcher;
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
public class SymbolPartitionOrderingBenchmark {

    @State(Scope.Benchmark)
    public static class PartitionState {

        @Param({"1", "4"})
        public int partitions;

        private SymbolPartitionDispatcher dispatcher;
        private AtomicInteger symbol;

        @Setup
        public void setUp() {
            dispatcher = new SymbolPartitionDispatcher(partitions);
            symbol = new AtomicInteger();
        }

        @TearDown
        public void tearDown() {
            dispatcher.close();
        }
    }

    @Benchmark
    public SymbolPartitionDispatcher.PartitionResult<Integer>
            publishAndComplete(final PartitionState state) {
        final int symbol = state.symbol.getAndIncrement();
        return state.dispatcher
                .submit(
                        symbol,
                        () -> CompletableFuture.completedFuture(symbol))
                .join();
    }
}
