package exchange.core2.core.simulation;

import exchange.core2.core.common.api.dma.DmaLifecycleResult;
import exchange.core2.core.common.api.dma.DmaOrderResult;
import exchange.core2.core.common.api.dma.DmaOrderStatus;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free cumulative counters and an approximate logarithmic latency
 * histogram suitable for simulation observability without a metrics backend.
 */
public final class ProductionSimulationMetrics {

    private final long startedNanos = System.nanoTime();
    private final OperationMetrics[] operations =
            new OperationMetrics[SimulationOperation.values().length];

    public ProductionSimulationMetrics() {
        for (int index = 0; index < operations.length; index++) {
            operations[index] = new OperationMetrics();
        }
    }

    long start(final SimulationOperation operation) {
        operation(operation).submitted.increment();
        return System.nanoTime();
    }

    void success(
            final SimulationOperation operation,
            final long operationStartedNanos,
            final DmaLifecycleResult result) {
        final OperationMetrics metrics = operation(operation);
        metrics.succeeded.increment();
        recordOutcome(metrics, result);
        metrics.latencies.record(System.nanoTime() - operationStartedNanos);
    }

    void portfolioFailure(
            final SimulationOperation operation,
            final long operationStartedNanos,
            final DmaLifecycleResult result) {
        final OperationMetrics metrics = operation(operation);
        metrics.failed.increment();
        metrics.portfolioPublicationFailures.increment();
        recordOutcome(metrics, result);
        metrics.latencies.record(System.nanoTime() - operationStartedNanos);
    }

    private static void recordOutcome(
            final OperationMetrics metrics,
            final DmaLifecycleResult result) {
        if (result.duplicateDelivery()) {
            metrics.duplicateDeliveries.increment();
            return;
        }

        final DmaOrderResult commandResult = result.commandResult();
        metrics.fills.add(commandResult.fills().size());
        commandResult.fills().forEach(fill -> metrics.filledQuantity.add(fill.quantity()));
        metrics.cancelledQuantity.add(commandResult.cancelledQuantity());
        final long rejectedQuantity =
                commandResult.rejectedQuantity() == 0
                        && result.orderState().status() == DmaOrderStatus.REJECTED
                        ? result.orderState().rejectedQuantity()
                        : commandResult.rejectedQuantity();
        metrics.rejectedQuantity.add(rejectedQuantity);
    }

    void success(final SimulationOperation operation, final long operationStartedNanos) {
        final OperationMetrics metrics = operation(operation);
        metrics.succeeded.increment();
        metrics.latencies.record(System.nanoTime() - operationStartedNanos);
    }

    void failure(final SimulationOperation operation, final long operationStartedNanos) {
        final OperationMetrics metrics = operation(operation);
        metrics.failed.increment();
        metrics.latencies.record(System.nanoTime() - operationStartedNanos);
    }

    public Snapshot snapshot() {
        final EnumMap<SimulationOperation, OperationSnapshot> operationSnapshots =
                new EnumMap<>(SimulationOperation.class);
        long submitted = 0;
        long succeeded = 0;
        long failed = 0;
        long portfolioPublicationFailures = 0;
        long duplicateDeliveries = 0;
        long fills = 0;
        long filledQuantity = 0;
        long cancelledQuantity = 0;
        long rejectedQuantity = 0;

        for (final SimulationOperation operation : SimulationOperation.values()) {
            final OperationSnapshot snapshot = operation(operation).snapshot();
            operationSnapshots.put(operation, snapshot);
            submitted += snapshot.submitted();
            succeeded += snapshot.succeeded();
            failed += snapshot.failed();
            portfolioPublicationFailures +=
                    snapshot.portfolioPublicationFailures();
            duplicateDeliveries += snapshot.duplicateDeliveries();
            fills += snapshot.fills();
            filledQuantity += snapshot.filledQuantity();
            cancelledQuantity += snapshot.cancelledQuantity();
            rejectedQuantity += snapshot.rejectedQuantity();
        }

        final long elapsedNanos = Math.max(1L, System.nanoTime() - startedNanos);
        return new Snapshot(
                elapsedNanos,
                submitted,
                succeeded,
                failed,
                portfolioPublicationFailures,
                duplicateDeliveries,
                fills,
                filledQuantity,
                cancelledQuantity,
                rejectedQuantity,
                succeeded * 1_000_000_000.0 / elapsedNanos,
                operationSnapshots);
    }

    private OperationMetrics operation(final SimulationOperation operation) {
        return operations[operation.ordinal()];
    }

    public record Snapshot(
            long elapsedNanos,
            long submitted,
            long succeeded,
            long failed,
            long portfolioPublicationFailures,
            long duplicateDeliveries,
            long fills,
            long filledQuantity,
            long cancelledQuantity,
            long rejectedQuantity,
            double successfulOperationsPerSecond,
            Map<SimulationOperation, OperationSnapshot> operations) {

        public Snapshot {
            operations = Map.copyOf(operations);
        }
    }

    public record OperationSnapshot(
            long submitted,
            long succeeded,
            long failed,
            long portfolioPublicationFailures,
            long duplicateDeliveries,
            long fills,
            long filledQuantity,
            long cancelledQuantity,
            long rejectedQuantity,
            long latencyP50Nanos,
            long latencyP95Nanos,
            long latencyP99Nanos,
            long latencyMaxNanos) {
    }

    private static final class OperationMetrics {

        private final LongAdder submitted = new LongAdder();
        private final LongAdder succeeded = new LongAdder();
        private final LongAdder failed = new LongAdder();
        private final LongAdder portfolioPublicationFailures =
                new LongAdder();
        private final LongAdder duplicateDeliveries = new LongAdder();
        private final LongAdder fills = new LongAdder();
        private final LongAdder filledQuantity = new LongAdder();
        private final LongAdder cancelledQuantity = new LongAdder();
        private final LongAdder rejectedQuantity = new LongAdder();
        private final LatencyHistogram latencies = new LatencyHistogram();

        private OperationSnapshot snapshot() {
            return new OperationSnapshot(
                    submitted.sum(),
                    succeeded.sum(),
                    failed.sum(),
                    portfolioPublicationFailures.sum(),
                    duplicateDeliveries.sum(),
                    fills.sum(),
                    filledQuantity.sum(),
                    cancelledQuantity.sum(),
                    rejectedQuantity.sum(),
                    latencies.percentile(50),
                    latencies.percentile(95),
                    latencies.percentile(99),
                    latencies.max());
        }
    }

    private static final class LatencyHistogram {

        private static final int BUCKETS = Long.SIZE;

        private final LongAdder[] counts = new LongAdder[BUCKETS];
        private final LongAdder total = new LongAdder();
        private final AtomicLong max = new AtomicLong();

        private LatencyHistogram() {
            for (int index = 0; index < BUCKETS; index++) {
                counts[index] = new LongAdder();
            }
        }

        private void record(final long value) {
            final long latency = Math.max(0L, value);
            counts[bucket(latency)].increment();
            total.increment();
            max.accumulateAndGet(latency, Math::max);
        }

        private long percentile(final int percentile) {
            final long samples = total.sum();
            if (samples == 0) {
                return 0L;
            }

            final long target = Math.max(1L, (samples * percentile + 99L) / 100L);
            long observed = 0L;
            for (int index = 0; index < counts.length; index++) {
                observed += counts[index].sum();
                if (observed >= target) {
                    return upperBound(index);
                }
            }
            return max();
        }

        private long max() {
            return max.get();
        }

        private static int bucket(final long value) {
            return value == 0L ? 0 : Long.SIZE - Long.numberOfLeadingZeros(value);
        }

        private static long upperBound(final int bucket) {
            if (bucket == 0) {
                return 0L;
            }
            if (bucket == Long.SIZE - 1) {
                return Long.MAX_VALUE;
            }
            return (1L << bucket) - 1L;
        }
    }
}
