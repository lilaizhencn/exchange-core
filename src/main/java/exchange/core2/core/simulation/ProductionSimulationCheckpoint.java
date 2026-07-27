package exchange.core2.core.simulation;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A committed native-core and DMA-lifecycle checkpoint.
 */
public record ProductionSimulationCheckpoint(
        long checkpointId,
        Path lifecyclePath) {

    public ProductionSimulationCheckpoint {
        if (checkpointId <= 0) {
            throw new IllegalArgumentException("checkpointId must be positive");
        }
        Objects.requireNonNull(lifecyclePath, "lifecyclePath");
    }
}
