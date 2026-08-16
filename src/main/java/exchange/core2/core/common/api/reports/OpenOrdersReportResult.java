package exchange.core2.core.common.api.reports;

import exchange.core2.core.common.OrderAction;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public final class OpenOrdersReportResult implements ReportResult {

    private static final int MAX_ORDERS = 100_000_000;

    private final List<OpenOrder> orders;

    public OpenOrdersReportResult(final List<OpenOrder> orders) {
        final ArrayList<OpenOrder> copied = new ArrayList<>(orders);
        final Set<Long> orderIds = new HashSet<>(copied.size());
        for (final OpenOrder order : copied) {
            if (!orderIds.add(order.orderId())) {
                throw new IllegalStateException("Duplicate open order " + order.orderId());
            }
        }
        this.orders = List.copyOf(copied);
    }

    public List<OpenOrder> getOrders() {
        return orders;
    }

    @Override
    public void writeMarshallable(final BytesOut bytes) {
        bytes.writeInt(orders.size());
        orders.forEach(order -> order.writeMarshallable(bytes));
    }

    static OpenOrdersReportResult merge(final Stream<BytesIn> sections) {
        final ArrayList<OpenOrder> merged = new ArrayList<>();
        sections.forEach(bytes -> {
            final int count = bytes.readInt();
            if (count < 0 || count > MAX_ORDERS) {
                throw new IllegalArgumentException("Invalid open-order report count " + count);
            }
            for (int index = 0; index < count; index++) {
                merged.add(new OpenOrder(bytes));
            }
        });
        return new OpenOrdersReportResult(merged);
    }

    public record OpenOrder(
            int symbolId,
            long orderId,
            long uid,
            OrderAction action,
            long price,
            long size,
            long filled,
            long reserveBidPrice) {

        public OpenOrder {
            if (symbolId <= 0 || orderId <= 0 || uid <= 0 || action == null || price <= 0
                    || size <= 0 || filled < 0 || filled >= size || reserveBidPrice < 0) {
                throw new IllegalArgumentException("Invalid open order report entry");
            }
        }

        private OpenOrder(final BytesIn bytes) {
            this(bytes.readInt(), bytes.readLong(), bytes.readLong(), OrderAction.of(bytes.readByte()),
                    bytes.readLong(), bytes.readLong(), bytes.readLong(), bytes.readLong());
        }

        private void writeMarshallable(final BytesOut bytes) {
            bytes.writeInt(symbolId);
            bytes.writeLong(orderId);
            bytes.writeLong(uid);
            bytes.writeByte(action.getCode());
            bytes.writeLong(price);
            bytes.writeLong(size);
            bytes.writeLong(filled);
            bytes.writeLong(reserveBidPrice);
        }
    }
}
