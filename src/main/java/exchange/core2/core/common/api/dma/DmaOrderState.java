package exchange.core2.core.common.api.dma;

import exchange.core2.core.common.cmd.CommandResultCode;

import java.util.Objects;

/**
 * Immutable lifecycle projection for one DMA order.
 */
public record DmaOrderState(
        DmaNewOrder order,
        DmaOrderStatus status,
        long filledQuantity,
        long cancelledQuantity,
        long rejectedQuantity,
        long remainingQuantity,
        long version) {

    public DmaOrderState {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(status, "status");
        if (filledQuantity < 0 || cancelledQuantity < 0 || rejectedQuantity < 0 || remainingQuantity < 0) {
            throw new IllegalArgumentException("lifecycle quantities must not be negative");
        }
        final long accountedQuantity = Math.addExact(
                Math.addExact(filledQuantity, cancelledQuantity),
                Math.addExact(rejectedQuantity, remainingQuantity));
        if (accountedQuantity != order.quantity()) {
            throw new IllegalArgumentException("lifecycle quantities must equal the current order quantity");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        validateStatus(
                status,
                filledQuantity,
                cancelledQuantity,
                rejectedQuantity,
                remainingQuantity,
                version);
    }

    public static DmaOrderState initial(final DmaNewOrder order) {
        return new DmaOrderState(order, DmaOrderStatus.NEW, 0, 0, 0, order.quantity(), 0);
    }

    public DmaOrderState applySubmitResult(final DmaOrderResult result) {
        requireResultOrder(result);
        if (result.resultCode() != CommandResultCode.SUCCESS) {
            return new DmaOrderState(
                    order,
                    DmaOrderStatus.REJECTED,
                    filledQuantity,
                    cancelledQuantity,
                    Math.addExact(rejectedQuantity, remainingQuantity),
                    0,
                    version + 1);
        }

        long newlyFilled = 0L;
        for (final DmaFill fill : result.fills()) {
            newlyFilled = Math.addExact(newlyFilled, fill.quantity());
        }

        final long nextFilled = Math.addExact(filledQuantity, newlyFilled);
        final long nextCancelled = Math.addExact(cancelledQuantity, result.cancelledQuantity());
        final long nextRejected = Math.addExact(rejectedQuantity, result.rejectedQuantity());
        final long nextRemaining = remainingAfter(nextFilled, nextCancelled, nextRejected);

        return new DmaOrderState(
                order,
                statusFor(nextFilled, nextCancelled, nextRejected, nextRemaining),
                nextFilled,
                nextCancelled,
                nextRejected,
                nextRemaining,
                version + 1);
    }

    public DmaOrderState applyMakerFill(final DmaFill fill) {
        Objects.requireNonNull(fill, "fill");
        if (fill.makerOrderId() != order.orderId() || fill.makerClientId() != order.clientId()) {
            throw new IllegalArgumentException("fill does not belong to lifecycle order " + order.orderId());
        }
        if (fill.quantity() <= 0) {
            throw new IllegalArgumentException("fill quantity must be positive");
        }
        if (status.isTerminal()) {
            throw new IllegalStateException("terminal order " + order.orderId() + " received a fill");
        }

        final long nextFilled = Math.addExact(filledQuantity, fill.quantity());
        final long nextRemaining = remainingAfter(nextFilled, cancelledQuantity, rejectedQuantity);
        final DmaOrderStatus nextStatus =
                fill.makerOrderComplete() || nextRemaining == 0
                        ? DmaOrderStatus.FILLED
                        : DmaOrderStatus.PARTIALLY_FILLED;

        return new DmaOrderState(
                order,
                nextStatus,
                nextFilled,
                cancelledQuantity,
                rejectedQuantity,
                nextRemaining,
                version + 1);
    }

    public DmaOrderState applyCancelResult(final DmaOrderResult result) {
        requireResultOrder(result);
        if (result.resultCode() != CommandResultCode.SUCCESS || result.cancelledQuantity() == 0) {
            return this;
        }

        final long nextCancelled = Math.addExact(cancelledQuantity, result.cancelledQuantity());
        final long nextRemaining = remainingAfter(filledQuantity, nextCancelled, rejectedQuantity);

        return new DmaOrderState(
                order,
                nextRemaining == 0
                        ? DmaOrderStatus.CANCELLED
                        : statusFor(filledQuantity, nextCancelled, rejectedQuantity, nextRemaining),
                filledQuantity,
                nextCancelled,
                rejectedQuantity,
                nextRemaining,
                version + 1);
    }

    /**
     * Applies a successful atomic price/total-quantity replacement and any
     * trades caused by its new price.
     */
    public DmaOrderState applyReplaceResult(
            final DmaReplaceOrder replacement,
            final DmaOrderResult result) {
        Objects.requireNonNull(replacement, "replacement");
        requireResultOrder(result);
        requireOwner(replacement);
        if (!(order instanceof DmaLimitOrder limitOrder)) {
            throw new IllegalStateException("only live limit orders can be replaced");
        }
        if (result.resultCode() != CommandResultCode.SUCCESS) {
            return this;
        }
        if (result.cancelledQuantity() != 0 || result.rejectedQuantity() != 0) {
            throw new IllegalArgumentException("successful replacement must contain only trade events");
        }

        long newlyFilled = 0L;
        for (final DmaFill fill : result.fills()) {
            newlyFilled = Math.addExact(newlyFilled, fill.quantity());
        }
        final long nextFilled = Math.addExact(filledQuantity, newlyFilled);
        final long nextRemaining = Math.subtractExact(replacement.newQuantity(), nextFilled);
        final DmaLimitOrder replacedOrder = new DmaLimitOrder(
                limitOrder.deliveryId(),
                limitOrder.orderId(),
                limitOrder.clientId(),
                limitOrder.symbol(),
                limitOrder.side(),
                replacement.newPrice(),
                replacement.newQuantity());

        return new DmaOrderState(
                replacedOrder,
                statusFor(nextFilled, 0, 0, nextRemaining),
                nextFilled,
                0,
                0,
                nextRemaining,
                version + 1);
    }

    private long remainingAfter(
            final long filled,
            final long cancelled,
            final long rejected) {
        return Math.subtractExact(
                order.quantity(),
                Math.addExact(Math.addExact(filled, cancelled), rejected));
    }

    private void requireResultOrder(final DmaOrderResult result) {
        Objects.requireNonNull(result, "result");
        if (result.orderId() != order.orderId()) {
            throw new IllegalArgumentException("command result does not belong to lifecycle order " + order.orderId());
        }
    }

    private void requireOwner(final DmaReplaceOrder replacement) {
        if (replacement.orderId() != order.orderId()
                || replacement.clientId() != order.clientId()
                || replacement.symbol() != order.symbol()
                || replacement.side() != order.side()) {
            throw new IllegalArgumentException("replacement does not own lifecycle order " + order.orderId());
        }
    }

    private static void validateStatus(
            final DmaOrderStatus status,
            final long filled,
            final long cancelled,
            final long rejected,
            final long remaining,
            final long version) {
        final boolean valid = switch (status) {
            case NEW ->
                    filled == 0 && cancelled == 0 && rejected == 0 && remaining > 0 && version == 0;
            case LIVE ->
                    filled == 0 && cancelled == 0 && rejected == 0 && remaining > 0 && version > 0;
            case PARTIALLY_FILLED ->
                    filled > 0 && cancelled == 0 && rejected == 0 && remaining > 0 && version > 0;
            case FILLED ->
                    filled > 0 && cancelled == 0 && rejected == 0 && remaining == 0 && version > 0;
            case CANCELLED ->
                    cancelled > 0 && rejected == 0 && remaining == 0 && version > 0;
            case REJECTED ->
                    rejected > 0 && remaining == 0 && version > 0;
        };
        if (!valid) {
            throw new IllegalArgumentException("lifecycle quantities and version are inconsistent with " + status);
        }
    }

    private static DmaOrderStatus statusFor(
            final long filled,
            final long cancelled,
            final long rejected,
            final long remaining) {
        if (remaining == 0) {
            if (rejected > 0) {
                return DmaOrderStatus.REJECTED;
            }
            if (cancelled > 0) {
                return DmaOrderStatus.CANCELLED;
            }
            return DmaOrderStatus.FILLED;
        }
        return filled > 0 ? DmaOrderStatus.PARTIALLY_FILLED : DmaOrderStatus.LIVE;
    }
}
