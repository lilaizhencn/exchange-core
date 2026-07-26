package exchange.core2.core.common.config;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;


/**
 * Order processing configuration
 */
@AllArgsConstructor
@Getter
@Builder
@ToString
public final class OrdersProcessingConfiguration {

    public static OrdersProcessingConfiguration DEFAULT = OrdersProcessingConfiguration.builder()
            .riskProcessingMode(RiskProcessingMode.FULL_PER_CURRENCY)
            .marginTradingMode(MarginTradingMode.MARGIN_TRADING_ENABLED)
            .build();

    private final RiskProcessingMode riskProcessingMode;
    private final MarginTradingMode marginTradingMode;

    public enum RiskProcessingMode {
        // risk processing is on, every currency/asset account is checked independently
        FULL_PER_CURRENCY,

        /**
         * Matching-engine-only processing for externally risk-managed order flow.
         * Symbols must be registered, but users, balances, positions, fees, and
         * post-trade accounting are not maintained by the core.
         */
        MATCHING_ONLY,

        /**
         * Risk checks are disabled, while the legacy post-trade accounting path
         * remains enabled.
         *
         * @deprecated use {@link #MATCHING_ONLY} for a true matching-only core
        */
        @Deprecated
        NO_RISK_PROCESSING;

        public boolean bypassesRiskChecks() {
            return this != FULL_PER_CURRENCY;
        }

        public boolean isMatchingOnly() {
            return this == MATCHING_ONLY;
        }
    }

    public enum MarginTradingMode {
        MARGIN_TRADING_DISABLED,
        MARGIN_TRADING_ENABLED
    }
}
