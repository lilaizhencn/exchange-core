package exchange.core2.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import exchange.core2.core.common.api.reports.OpenOrdersReportResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenOrdersReportResultTest {

    @Test
    void preservesLinearReportOrderWithoutSorting() {
        var later = order(2, 20);
        var earlier = order(1, 10);

        var result = new OpenOrdersReportResult(List.of(later, earlier));

        assertEquals(List.of(later, earlier), result.getOrders());
    }

    @Test
    void rejectsDuplicateOrderIdsInLinearPass() {
        var exception = assertThrows(IllegalStateException.class,
                () -> new OpenOrdersReportResult(List.of(order(1, 10), order(2, 10))));
        assertEquals("Duplicate open order 10", exception.getMessage());
    }

    private static OpenOrdersReportResult.OpenOrder order(int symbolId, long orderId) {
        return new OpenOrdersReportResult.OpenOrder(
                symbolId, orderId, 7, OrderAction.BID, 100, 3, 1, 100);
    }
}
