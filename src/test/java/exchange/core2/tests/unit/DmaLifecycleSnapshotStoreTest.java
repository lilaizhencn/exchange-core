package exchange.core2.tests.unit;

import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.api.dma.DmaCancelOrder;
import exchange.core2.core.common.api.dma.DmaFill;
import exchange.core2.core.common.api.dma.DmaLifecycleResult;
import exchange.core2.core.common.api.dma.DmaLifecycleSnapshot;
import exchange.core2.core.common.api.dma.DmaLimitOrder;
import exchange.core2.core.common.api.dma.DmaOrderResult;
import exchange.core2.core.common.api.dma.DmaOrderState;
import exchange.core2.core.common.api.dma.DmaOrderStatus;
import exchange.core2.core.common.api.dma.DmaProtectedMarketOrder;
import exchange.core2.core.common.api.dma.DmaReplaceOrder;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.simulation.DmaLifecycleSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DmaLifecycleSnapshotStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldRoundTripAndDetectCorruptedCheckpoint() throws IOException {
        final DmaLifecycleSnapshotStore store =
                new DmaLifecycleSnapshotStore(
                        temporaryDirectory,
                        "simulation-test");
        final DmaLifecycleSnapshot snapshot = snapshotWithEveryDeliveryType();
        final Path checkpoint = store.save(101, snapshot);

        assertEquals(snapshot, store.load(101));

        final byte[] corrupted = Files.readAllBytes(checkpoint);
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(checkpoint, corrupted);

        assertThrows(IOException.class, () -> store.load(101));
    }

    private static DmaLifecycleSnapshot snapshotWithEveryDeliveryType() {
        final DmaLimitOrder original =
                new DmaLimitOrder(1, 10, 20, 30, OrderAction.ASK, 100, 10);
        final DmaOrderState live =
                new DmaOrderState(
                        original,
                        DmaOrderStatus.LIVE,
                        0,
                        0,
                        0,
                        10,
                        1);
        final DmaLifecycleResult submitted =
                result(1, 10, List.of(), 0, 0, live);

        final DmaReplaceOrder replace =
                new DmaReplaceOrder(
                        2,
                        10,
                        20,
                        30,
                        OrderAction.ASK,
                        101,
                        12);
        final DmaLimitOrder replacedOrder =
                new DmaLimitOrder(1, 10, 20, 30, OrderAction.ASK, 101, 12);
        final DmaOrderState replaced =
                new DmaOrderState(
                        replacedOrder,
                        DmaOrderStatus.LIVE,
                        0,
                        0,
                        0,
                        12,
                        2);
        final DmaLifecycleResult replacement =
                result(2, 10, List.of(), 0, 0, replaced);

        final DmaCancelOrder cancel = new DmaCancelOrder(3, 10, 20, 30);
        final DmaOrderState cancelled =
                new DmaOrderState(
                        replacedOrder,
                        DmaOrderStatus.CANCELLED,
                        0,
                        12,
                        0,
                        0,
                        3);
        final DmaLifecycleResult cancellation =
                result(3, 10, List.of(), 12, 0, cancelled);

        final DmaProtectedMarketOrder protectedOrder =
                new DmaProtectedMarketOrder(
                        4,
                        11,
                        21,
                        30,
                        OrderAction.BID,
                        101,
                        3);
        final DmaFill fill = new DmaFill(10, 20, 101, 2, false, false);
        final DmaOrderState rejected =
                new DmaOrderState(
                        protectedOrder,
                        DmaOrderStatus.REJECTED,
                        2,
                        0,
                        1,
                        0,
                        1);
        final DmaLifecycleResult protectedResult =
                result(4, 11, List.of(fill), 0, 1, rejected);

        return new DmaLifecycleSnapshot(
                Map.of(10L, cancelled, 11L, rejected),
                List.of(
                        new DmaLifecycleSnapshot.CompletedDelivery(
                                original,
                                submitted),
                        new DmaLifecycleSnapshot.CompletedDelivery(
                                replace,
                                replacement),
                        new DmaLifecycleSnapshot.CompletedDelivery(
                                cancel,
                                cancellation),
                        new DmaLifecycleSnapshot.CompletedDelivery(
                                protectedOrder,
                                protectedResult)));
    }

    private static DmaLifecycleResult result(
            final long deliveryId,
            final long orderId,
            final List<DmaFill> fills,
            final long cancelled,
            final long rejected,
            final DmaOrderState state) {
        return new DmaLifecycleResult(
                deliveryId,
                new DmaOrderResult(
                        orderId,
                        CommandResultCode.SUCCESS,
                        fills,
                        cancelled,
                        rejected),
                state,
                false);
    }
}
