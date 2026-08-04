package exchange.core2.core.simulation;

import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.orderbook.OrderBookDirectImpl;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Runtime and persistence settings for a production simulation.
 *
 * @param journalingEnabled write a journal (WAL) of every command, so state can
 *                          be recovered by replaying it onto the last snapshot
 *                          instead of snapshotting on the command path. The
 *                          journal is a Disruptor stage running in parallel
 *                          with risk and matching, so it costs far less than a
 *                          synchronous snapshot. Off by default because a
 *                          matching-only local run does not need the write
 *                          volume.
 */
public record ProductionSimulationConfiguration(
        String exchangeId,
        Path storageDirectory,
        int symbolPartitions,
        PerformanceConfiguration performanceConfiguration,
        boolean journalingEnabled) {

    public ProductionSimulationConfiguration {
        Objects.requireNonNull(exchangeId, "exchangeId");
        Objects.requireNonNull(storageDirectory, "storageDirectory");
        Objects.requireNonNull(performanceConfiguration, "performanceConfiguration");
        if (!exchangeId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(
                    "exchangeId must contain only letters, digits, dot, underscore or dash");
        }
        if (symbolPartitions <= 0 || Integer.bitCount(symbolPartitions) != 1) {
            throw new IllegalArgumentException("symbolPartitions must be a positive power of two");
        }
        if (performanceConfiguration.getMatchingEnginesNum() != symbolPartitions) {
            throw new IllegalArgumentException(
                    "matchingEnginesNum must equal symbolPartitions");
        }
        storageDirectory = storageDirectory.toAbsolutePath().normalize();
    }

    public static ProductionSimulationConfiguration create(
            final String exchangeId,
            final Path storageDirectory,
            final int symbolPartitions) {
        return create(exchangeId, storageDirectory, symbolPartitions, false);
    }

    public static ProductionSimulationConfiguration create(
            final String exchangeId,
            final Path storageDirectory,
            final int symbolPartitions,
            final boolean journalingEnabled) {
        return new ProductionSimulationConfiguration(
                exchangeId,
                storageDirectory,
                symbolPartitions,
                PerformanceConfiguration.baseBuilder()
                        .ringBufferSize(64 * 1024)
                        .matchingEnginesNum(symbolPartitions)
                        .riskEnginesNum(1)
                        .msgsInGroupLimit(4_096)
                        .maxGroupDurationNs(4_000_000)
                        .orderBookFactory(OrderBookDirectImpl::new)
                        .build(),
                journalingEnabled);
    }

    /**
     * @return this configuration with journaling switched on.
     */
    public ProductionSimulationConfiguration withJournaling() {
        return new ProductionSimulationConfiguration(
                exchangeId, storageDirectory, symbolPartitions, performanceConfiguration, true);
    }
}
