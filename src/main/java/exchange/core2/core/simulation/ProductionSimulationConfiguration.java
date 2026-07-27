package exchange.core2.core.simulation;

import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.orderbook.OrderBookDirectImpl;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Runtime and persistence settings for a production simulation.
 */
public record ProductionSimulationConfiguration(
        String exchangeId,
        Path storageDirectory,
        int symbolPartitions,
        PerformanceConfiguration performanceConfiguration) {

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
                        .build());
    }
}
