package exchange.core2.core.simulation;

import exchange.core2.core.common.config.OrdersProcessingConfiguration;

import java.util.Objects;

/**
 * Optional accounting mode for a production simulation.
 */
public record ProductionSimulationAccounting(
        Mode mode,
        EmporiaPortfolioGateway portfolioGateway) {

    public ProductionSimulationAccounting {
        Objects.requireNonNull(mode, "mode");
        if (mode == Mode.FULL_EQUITY_RISK) {
            Objects.requireNonNull(
                    portfolioGateway,
                    "portfolioGateway is required for full equity risk");
        } else if (portfolioGateway != null) {
            throw new IllegalArgumentException(
                    "matching-only mode must not define a portfolio gateway");
        }
    }

    public static ProductionSimulationAccounting matchingOnly() {
        return new ProductionSimulationAccounting(Mode.MATCHING_ONLY, null);
    }

    public static ProductionSimulationAccounting fullEquityRisk(
            final EmporiaPortfolioGateway portfolioGateway) {
        return new ProductionSimulationAccounting(
                Mode.FULL_EQUITY_RISK,
                portfolioGateway);
    }

    public boolean isFullEquityRisk() {
        return mode == Mode.FULL_EQUITY_RISK;
    }

    OrdersProcessingConfiguration ordersProcessingConfiguration() {
        return OrdersProcessingConfiguration.builder()
                .riskProcessingMode(
                        isFullEquityRisk()
                                ? OrdersProcessingConfiguration.RiskProcessingMode
                                        .FULL_PER_CURRENCY
                                : OrdersProcessingConfiguration.RiskProcessingMode
                                        .MATCHING_ONLY)
                .marginTradingMode(
                        OrdersProcessingConfiguration.MarginTradingMode
                                .MARGIN_TRADING_DISABLED)
                .build();
    }

    public enum Mode {
        MATCHING_ONLY,
        FULL_EQUITY_RISK
    }
}
