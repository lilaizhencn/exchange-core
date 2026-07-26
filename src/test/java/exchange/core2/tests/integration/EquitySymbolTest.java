package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.OrdersProcessingConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EquitySymbolTest {

    private static final int AAPL_USD = 10_001;
    private static final int AAPL_ASSET = 20_001;
    private static final int USD = 840;
    private static final long BUYER = 101;
    private static final long SELLER = 102;

    private static final CoreSymbolSpecification AAPL = CoreSymbolSpecification.builder()
            .symbolId(AAPL_USD)
            .type(SymbolType.EQUITY)
            .baseCurrency(AAPL_ASSET)
            .quoteCurrency(USD)
            .baseScaleK(1)
            .quoteScaleK(1)
            .takerFee(0)
            .makerFee(0)
            .build();

    @Test
    @Timeout(5)
    void shouldTradeEquityUsingFullyFundedAssetAndCashAccounts() throws Exception {
        final OrdersProcessingConfiguration cashOnly = OrdersProcessingConfiguration.builder()
                .riskProcessingMode(OrdersProcessingConfiguration.RiskProcessingMode.FULL_PER_CURRENCY)
                .marginTradingMode(OrdersProcessingConfiguration.MarginTradingMode.MARGIN_TRADING_DISABLED)
                .build();

        try (ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT, cashOnly)) {
            container.addSymbol(AAPL);
            container.createUserWithMoney(SELLER, AAPL_ASSET, 5);
            container.createUserWithMoney(BUYER, USD, 499);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(SELLER)
                    .orderId(1)
                    .symbol(AAPL_USD)
                    .price(100)
                    .size(5)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .build(), CommandResultCode.SUCCESS);

            final ApiPlaceOrder buyFiveShares = ApiPlaceOrder.builder()
                    .uid(BUYER)
                    .orderId(2)
                    .symbol(AAPL_USD)
                    .price(100)
                    .reservePrice(100)
                    .size(5)
                    .action(OrderAction.BID)
                    .orderType(OrderType.IOC)
                    .build();

            container.submitCommandSync(buyFiveShares, CommandResultCode.RISK_NSF);
            container.addMoneyToUser(BUYER, USD, 1);
            container.submitCommandSync(buyFiveShares, CommandResultCode.SUCCESS);

            container.validateUserState(BUYER, buyer -> {
                assertEquals(0L, buyer.getAccounts().get(USD));
                assertEquals(5L, buyer.getAccounts().get(AAPL_ASSET));
            });
            container.validateUserState(SELLER, seller -> {
                assertEquals(500L, seller.getAccounts().get(USD));
                assertEquals(0L, seller.getAccounts().get(AAPL_ASSET));
            });
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
        }
    }
}
