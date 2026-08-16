package exchange.core2.core.common.api.reports;

import exchange.core2.core.common.IOrder;
import exchange.core2.core.processors.MatchingEngineRouter;
import exchange.core2.core.processors.RiskEngine;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;

import java.util.ArrayList;
import java.util.Optional;
import java.util.stream.Stream;

public final class OpenOrdersReportQuery implements ReportQuery<OpenOrdersReportResult> {

    public OpenOrdersReportQuery() {
    }

    public OpenOrdersReportQuery(final BytesIn ignored) {
    }

    @Override
    public int getReportTypeCode() {
        return ReportType.OPEN_ORDERS.getCode();
    }

    @Override
    public OpenOrdersReportResult createResult(final Stream<BytesIn> sections) {
        return OpenOrdersReportResult.merge(sections);
    }

    @Override
    public Optional<OpenOrdersReportResult> process(final MatchingEngineRouter matchingEngine) {
        final ArrayList<OpenOrdersReportResult.OpenOrder> orders = new ArrayList<>();
        matchingEngine.getOrderBooks().forEach(orderBook -> {
            final int symbolId = orderBook.getSymbolSpec().symbolId;
            orderBook.askOrdersStream(true).forEach(order -> orders.add(copy(symbolId, order)));
            orderBook.bidOrdersStream(true).forEach(order -> orders.add(copy(symbolId, order)));
        });
        return Optional.of(new OpenOrdersReportResult(orders));
    }

    @Override
    public Optional<OpenOrdersReportResult> process(final RiskEngine riskEngine) {
        return Optional.empty();
    }

    @Override
    public void writeMarshallable(final BytesOut bytes) {
    }

    private static OpenOrdersReportResult.OpenOrder copy(final int symbolId, final IOrder order) {
        return new OpenOrdersReportResult.OpenOrder(symbolId, order.getOrderId(), order.getUid(),
                order.getAction(), order.getPrice(), order.getSize(), order.getFilled(),
                order.getReserveBidPrice());
    }
}
