package exchange.core2.core.simulation;

import exchange.core2.core.common.api.dma.DmaLifecycleResult;

import java.util.Objects;

/**
 * Lifecycle response with the symbol-lane position used to publish it.
 */
public record ProductionSimulationResult(
        SimulationOperation operation,
        int partition,
        long partitionSequence,
        DmaLifecycleResult lifecycleResult) {

    public ProductionSimulationResult {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(lifecycleResult, "lifecycleResult");
        if (partition < 0) {
            throw new IllegalArgumentException("partition must not be negative");
        }
        if (partitionSequence <= 0) {
            throw new IllegalArgumentException("partitionSequence must be positive");
        }
    }
}
