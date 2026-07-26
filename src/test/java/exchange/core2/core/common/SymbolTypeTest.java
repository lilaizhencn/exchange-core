package exchange.core2.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SymbolTypeTest {

    @Test
    void shouldPreserveExistingCodesAndAssignEquityCode() {
        assertEquals(SymbolType.CURRENCY_EXCHANGE_PAIR, SymbolType.of(0));
        assertEquals(SymbolType.FUTURES_CONTRACT, SymbolType.of(1));
        assertEquals(SymbolType.OPTION, SymbolType.of(2));
        assertEquals(SymbolType.EQUITY, SymbolType.of(3));
        assertEquals((byte) 3, SymbolType.EQUITY.getCode());
    }

    @Test
    void shouldClassifyOnlyFullyFundedInstrumentsAsCashMarkets() {
        assertTrue(SymbolType.CURRENCY_EXCHANGE_PAIR.isCashMarket());
        assertTrue(SymbolType.EQUITY.isCashMarket());
        assertFalse(SymbolType.FUTURES_CONTRACT.isCashMarket());
        assertFalse(SymbolType.OPTION.isCashMarket());
    }

    @Test
    void shouldRejectUnknownCode() {
        assertThrows(IllegalStateException.class, () -> SymbolType.of(4));
    }
}
