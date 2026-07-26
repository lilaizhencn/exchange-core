package exchange.core2.core.orderbook;

import exchange.core2.collections.objpool.ObjectsPool;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.config.LoggingConfiguration;

public final class OrderBookDirectImplEquityTest extends OrderBookDirectImplTest {

    private static final CoreSymbolSpecification EQUITY = CoreSymbolSpecification.builder()
            .symbolId(10_001)
            .type(SymbolType.EQUITY)
            .baseCurrency(20_001)
            .quoteCurrency(840)
            .baseScaleK(1)
            .quoteScaleK(1)
            .takerFee(0)
            .makerFee(0)
            .build();

    @Override
    protected IOrderBook createNewOrderBook() {
        return new OrderBookDirectImpl(
                getCoreSymbolSpec(),
                ObjectsPool.createDefaultTestPool(),
                OrderBookEventsHelper.NON_POOLED_EVENTS_HELPER,
                LoggingConfiguration.DEFAULT);
    }

    @Override
    protected CoreSymbolSpecification getCoreSymbolSpec() {
        return EQUITY;
    }
}
