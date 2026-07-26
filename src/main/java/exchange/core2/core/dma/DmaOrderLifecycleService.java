package exchange.core2.core.dma;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.api.dma.DmaCancelOrder;
import exchange.core2.core.common.api.dma.DmaDeliveryRequest;
import exchange.core2.core.common.api.dma.DmaFill;
import exchange.core2.core.common.api.dma.DmaLifecycleResult;
import exchange.core2.core.common.api.dma.DmaLifecycleSnapshot;
import exchange.core2.core.common.api.dma.DmaLimitOrder;
import exchange.core2.core.common.api.dma.DmaOrderResult;
import exchange.core2.core.common.api.dma.DmaOrderState;
import exchange.core2.core.common.api.dma.DmaOrderStatus;
import exchange.core2.core.common.cmd.CommandResultCode;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Thread-safe DMA lifecycle projection and idempotent delivery boundary.
 *
 * <p>The exchange ring buffer remains the command linearization point. This
 * service records that order in immutable lifecycle states, updates resting
 * maker orders from taker fills, and delays cancellation while submission is
 * still pending.</p>
 */
public final class DmaOrderLifecycleService {

    private final ExchangeApi exchangeApi;
    private final Object lock = new Object();

    private final Map<Long, DmaOrderState> orders = new HashMap<>();
    private final Map<DeliveryKey, InFlightDelivery> inFlightDeliveries = new HashMap<>();
    private final Map<DeliveryKey, DmaLifecycleSnapshot.CompletedDelivery> completedDeliveries = new HashMap<>();
    private final Map<Long, CompletableFuture<DmaLifecycleResult>> submissionsByOrder = new HashMap<>();

    public DmaOrderLifecycleService(final ExchangeApi exchangeApi) {
        this.exchangeApi = Objects.requireNonNull(exchangeApi, "exchangeApi");
    }

    /**
     * Submits a new DMA limit order once for a stable delivery identifier.
     */
    public CompletableFuture<DmaLifecycleResult> submit(final DmaLimitOrder request) {
        Objects.requireNonNull(request, "request");
        final DeliveryKey key = DeliveryKey.submit(request.deliveryId());

        synchronized (lock) {
            final CompletableFuture<DmaLifecycleResult> duplicate = duplicateDelivery(key, request);
            if (duplicate != null) {
                return duplicate;
            }
            if (orders.containsKey(request.orderId())) {
                throw new IllegalStateException("order " + request.orderId() + " already exists in the lifecycle");
            }

            final CompletableFuture<DmaLifecycleResult> lifecycleFuture = new CompletableFuture<>();
            orders.put(request.orderId(), DmaOrderState.initial(request));
            inFlightDeliveries.put(key, new InFlightDelivery(request, lifecycleFuture));
            submissionsByOrder.put(request.orderId(), lifecycleFuture);

            try {
                exchangeApi.submitDmaLimitOrder(request)
                        .whenComplete((result, error) ->
                                completeSubmit(key, request, lifecycleFuture, result, error));
            } catch (final RuntimeException error) {
                orders.remove(request.orderId());
                inFlightDeliveries.remove(key);
                submissionsByOrder.remove(request.orderId());
                lifecycleFuture.completeExceptionally(error);
            }

            return lifecycleFuture;
        }
    }

    /**
     * Cancels an order once. If its submit is still pending, cancellation is
     * published only after the submit result is observed.
     */
    public CompletableFuture<DmaLifecycleResult> cancel(final DmaCancelOrder request) {
        Objects.requireNonNull(request, "request");
        final DeliveryKey key = DeliveryKey.cancel(request.deliveryId());
        final CompletableFuture<DmaLifecycleResult> lifecycleFuture;
        final CompletableFuture<DmaLifecycleResult> pendingSubmit;

        synchronized (lock) {
            final CompletableFuture<DmaLifecycleResult> duplicate = duplicateDelivery(key, request);
            if (duplicate != null) {
                return duplicate;
            }

            final DmaOrderState state = requireOrder(request);
            lifecycleFuture = new CompletableFuture<>();
            inFlightDeliveries.put(key, new InFlightDelivery(request, lifecycleFuture));

            pendingSubmit = state.status() == DmaOrderStatus.NEW
                    ? submissionsByOrder.get(request.orderId())
                    : null;

            if (state.status() == DmaOrderStatus.NEW && pendingSubmit == null) {
                inFlightDeliveries.remove(key);
                throw new IllegalStateException("pending submission is missing for order " + request.orderId());
            }
        }

        if (pendingSubmit == null) {
            dispatchCancel(key, request, lifecycleFuture);
        } else {
            pendingSubmit.whenComplete((ignored, submitError) -> {
                if (submitError != null) {
                    failCancel(key, lifecycleFuture, submitError);
                } else {
                    dispatchCancel(key, request, lifecycleFuture);
                }
            });
        }

        return lifecycleFuture;
    }

    public Optional<DmaOrderState> findOrder(final long orderId) {
        synchronized (lock) {
            return Optional.ofNullable(orders.get(orderId));
        }
    }

    public DmaOrderState getOrder(final long orderId) {
        return findOrder(orderId)
                .orElseThrow(() -> new IllegalArgumentException("unknown lifecycle order " + orderId));
    }

    /**
     * Creates a recovery checkpoint. In-flight delivery responses must first be
     * drained so the checkpoint represents a command boundary.
     */
    public DmaLifecycleSnapshot snapshot() {
        synchronized (lock) {
            if (!inFlightDeliveries.isEmpty()) {
                throw new IllegalStateException("can not snapshot while DMA deliveries are in flight");
            }

            final List<DmaLifecycleSnapshot.CompletedDelivery> deliveries =
                    completedDeliveries.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey(
                                    Comparator.comparing(DeliveryKey::type)
                                            .thenComparingLong(DeliveryKey::deliveryId)))
                            .map(Map.Entry::getValue)
                            .toList();

            return new DmaLifecycleSnapshot(orders, deliveries);
        }
    }

    /**
     * Restores lifecycle and deduplication state into a new, unused service.
     */
    public void restore(final DmaLifecycleSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");

        synchronized (lock) {
            if (!orders.isEmpty() || !inFlightDeliveries.isEmpty() || !completedDeliveries.isEmpty()) {
                throw new IllegalStateException("lifecycle service must be empty before recovery");
            }

            snapshot.orders().forEach((orderId, state) -> {
                if (orderId != state.order().orderId()) {
                    throw new IllegalArgumentException("snapshot order key does not match its state");
                }
                if (state.status() == DmaOrderStatus.NEW) {
                    throw new IllegalArgumentException("snapshot must not contain an in-flight NEW order");
                }
                orders.put(orderId, state);
            });

            for (final DmaLifecycleSnapshot.CompletedDelivery delivery : snapshot.completedDeliveries()) {
                final DeliveryKey key = DeliveryKey.of(delivery.request());
                if (completedDeliveries.putIfAbsent(key, delivery) != null) {
                    throw new IllegalArgumentException("snapshot contains duplicate delivery " + key);
                }
            }
        }
    }

    public static DmaOrderLifecycleService recover(
            final ExchangeApi exchangeApi,
            final DmaLifecycleSnapshot snapshot) {
        final DmaOrderLifecycleService recovered = new DmaOrderLifecycleService(exchangeApi);
        recovered.restore(snapshot);
        return recovered;
    }

    private void completeSubmit(
            final DeliveryKey key,
            final DmaLimitOrder request,
            final CompletableFuture<DmaLifecycleResult> lifecycleFuture,
            final DmaOrderResult commandResult,
            final Throwable error) {
        if (error != null) {
            synchronized (lock) {
                orders.remove(request.orderId());
                inFlightDeliveries.remove(key);
                submissionsByOrder.remove(request.orderId());
            }
            lifecycleFuture.completeExceptionally(error);
            return;
        }

        final DmaLifecycleResult lifecycleResult;
        synchronized (lock) {
            DmaOrderState state = orders.get(request.orderId()).applySubmitResult(commandResult);
            orders.put(request.orderId(), state);

            for (final DmaFill fill : commandResult.fills()) {
                final DmaOrderState makerState = orders.get(fill.makerOrderId());
                if (makerState != null) {
                    orders.put(fill.makerOrderId(), makerState.applyMakerFill(fill));
                }
            }

            lifecycleResult = new DmaLifecycleResult(
                    request.deliveryId(),
                    commandResult,
                    state,
                    false);
            completeDelivery(key, request, lifecycleResult);
            submissionsByOrder.remove(request.orderId());
        }

        lifecycleFuture.complete(lifecycleResult);
    }

    private void dispatchCancel(
            final DeliveryKey key,
            final DmaCancelOrder request,
            final CompletableFuture<DmaLifecycleResult> lifecycleFuture) {
        DmaLifecycleResult terminalResult = null;
        Throwable failure = null;

        synchronized (lock) {
            final DmaOrderState state = requireOrder(request);
            if (state.status().isTerminal()) {
                final DmaOrderResult commandResult = new DmaOrderResult(
                        request.orderId(),
                        CommandResultCode.MATCHING_UNKNOWN_ORDER_ID,
                        List.of(),
                        0,
                        0);
                terminalResult = new DmaLifecycleResult(
                        request.deliveryId(),
                        commandResult,
                        state,
                        false);
                completeDelivery(key, request, terminalResult);
            } else {
                try {
                    exchangeApi.cancelDmaOrder(request)
                            .whenComplete((result, error) ->
                                    completeCancel(key, request, lifecycleFuture, result, error));
                } catch (final RuntimeException error) {
                    inFlightDeliveries.remove(key);
                    failure = error;
                }
            }
        }

        if (terminalResult != null) {
            lifecycleFuture.complete(terminalResult);
        } else if (failure != null) {
            lifecycleFuture.completeExceptionally(failure);
        }
    }

    private void completeCancel(
            final DeliveryKey key,
            final DmaCancelOrder request,
            final CompletableFuture<DmaLifecycleResult> lifecycleFuture,
            final DmaOrderResult commandResult,
            final Throwable error) {
        if (error != null) {
            failCancel(key, lifecycleFuture, error);
            return;
        }

        final DmaLifecycleResult lifecycleResult;
        synchronized (lock) {
            final DmaOrderState currentState = requireOrder(request);
            final DmaOrderState nextState = currentState.applyCancelResult(commandResult);
            orders.put(request.orderId(), nextState);

            lifecycleResult = new DmaLifecycleResult(
                    request.deliveryId(),
                    commandResult,
                    nextState,
                    false);
            completeDelivery(key, request, lifecycleResult);
        }

        lifecycleFuture.complete(lifecycleResult);
    }

    private void failCancel(
            final DeliveryKey key,
            final CompletableFuture<DmaLifecycleResult> lifecycleFuture,
            final Throwable error) {
        synchronized (lock) {
            inFlightDeliveries.remove(key);
        }
        lifecycleFuture.completeExceptionally(error);
    }

    private DmaOrderState requireOrder(final DmaCancelOrder request) {
        final DmaOrderState state = orders.get(request.orderId());
        if (state == null) {
            throw new IllegalArgumentException("unknown lifecycle order " + request.orderId());
        }
        if (state.order().clientId() != request.clientId() || state.order().symbol() != request.symbol()) {
            throw new IllegalArgumentException("cancel request does not own order " + request.orderId());
        }
        return state;
    }

    private CompletableFuture<DmaLifecycleResult> duplicateDelivery(
            final DeliveryKey key,
            final DmaDeliveryRequest request) {
        final DmaLifecycleSnapshot.CompletedDelivery completed = completedDeliveries.get(key);
        if (completed != null) {
            requireSameDelivery(completed.request(), request);
            return CompletableFuture.completedFuture(completed.result().asDuplicateDelivery());
        }

        final InFlightDelivery inFlight = inFlightDeliveries.get(key);
        if (inFlight != null) {
            requireSameDelivery(inFlight.request(), request);
            return inFlight.future().thenApply(DmaLifecycleResult::asDuplicateDelivery);
        }

        return null;
    }

    private void completeDelivery(
            final DeliveryKey key,
            final DmaDeliveryRequest request,
            final DmaLifecycleResult result) {
        inFlightDeliveries.remove(key);
        completedDeliveries.put(
                key,
                new DmaLifecycleSnapshot.CompletedDelivery(request, result));
    }

    private static void requireSameDelivery(
            final DmaDeliveryRequest existing,
            final DmaDeliveryRequest redelivered) {
        if (!existing.equals(redelivered)) {
            throw new IllegalArgumentException(
                    "delivery " + redelivered.deliveryId() + " was reused with a different request");
        }
    }

    private enum DeliveryType {
        SUBMIT,
        CANCEL
    }

    private record DeliveryKey(DeliveryType type, long deliveryId) {

        private static DeliveryKey submit(final long deliveryId) {
            return new DeliveryKey(DeliveryType.SUBMIT, deliveryId);
        }

        private static DeliveryKey cancel(final long deliveryId) {
            return new DeliveryKey(DeliveryType.CANCEL, deliveryId);
        }

        private static DeliveryKey of(final DmaDeliveryRequest request) {
            if (request instanceof DmaLimitOrder) {
                return submit(request.deliveryId());
            }
            if (request instanceof DmaCancelOrder) {
                return cancel(request.deliveryId());
            }
            throw new IllegalArgumentException("unsupported DMA delivery " + request.getClass().getName());
        }
    }

    private record InFlightDelivery(
            DmaDeliveryRequest request,
            CompletableFuture<DmaLifecycleResult> future) {
    }
}
