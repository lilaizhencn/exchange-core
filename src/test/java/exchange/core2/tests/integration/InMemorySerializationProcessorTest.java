package exchange.core2.tests.integration;

import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.reports.OpenOrdersReportQuery;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import exchange.core2.core.processors.journaling.InMemorySerializationProcessor;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static exchange.core2.tests.util.TestConstants.SYMBOLSPEC_ETH_XBT;
import static exchange.core2.tests.util.TestConstants.UID_1;
import static exchange.core2.tests.util.TestConstants.UID_2;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemorySerializationProcessorTest {

    @Test
    @Timeout(30)
    void shouldExportAndRestoreNativeSnapshotWithFifoPriority() throws Exception {
        final String exchangeId = "MEMORY_SNAPSHOT";
        final long snapshotId = 8_001L;
        final int symbol = SYMBOLSPEC_ETH_XBT.symbolId;
        final InMemorySerializationProcessor source =
                new InMemorySerializationProcessor();
        final SerializationConfiguration sourceSerialization =
                serialization(source);
        final int originalStateHash;
        final List<InMemorySerializationProcessor.SerializedModule> modules;

        try (ExchangeTestContainer container = ExchangeTestContainer.create(
                PerformanceConfiguration.DEFAULT,
                InitialStateConfiguration.cleanStart(exchangeId),
                sourceSerialization)) {
            container.initBasicSymbols();
            container.initBasicUsers();
            placeAsk(container, 501, UID_1, 1_600, 3);
            placeAsk(container, 502, UID_2, 1_600, 5);

            final var openOrders = container.getApi().processReport(new OpenOrdersReportQuery(), 0).join();
            assertEquals(List.of(501L, 502L), openOrders.getOrders().stream()
                    .map(order -> order.orderId()).toList());
            assertEquals(symbol, openOrders.getOrders().getFirst().symbolId());
            assertEquals(UID_1, openOrders.getOrders().getFirst().uid());

            assertEquals(
                    CommandResultCode.SUCCESS,
                    container.getApi().submitCommandAsync(
                            ApiPersistState.builder().dumpId(snapshotId).build()).join());
            originalStateHash = container.requestStateHash();
            modules = source.exportSnapshot(snapshotId);
            assertFalse(modules.isEmpty());
        }

        final InMemorySerializationProcessor restoredStorage =
                new InMemorySerializationProcessor();
        restoredStorage.importSnapshot(modules);

        try (ExchangeTestContainer recovered = ExchangeTestContainer.create(
                PerformanceConfiguration.DEFAULT,
                InitialStateConfiguration.fromSnapshotOnly(exchangeId, snapshotId, 0),
                serialization(restoredStorage))) {
            assertEquals(originalStateHash, recovered.requestStateHash());
            assertEquals(8, recovered.requestCurrentOrderBook(symbol).askVolumes[0]);

            recovered.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(UID_2)
                            .orderId(503)
                            .price(1_600)
                            .reservePrice(1_600)
                            .size(4)
                            .action(OrderAction.BID)
                            .orderType(OrderType.IOC)
                            .symbol(symbol)
                            .build(),
                    CommandResultCode.SUCCESS);

            assertEquals(4, recovered.requestCurrentOrderBook(symbol).askVolumes[0]);
            assertTrue(recovered.totalBalanceReport().isGlobalBalancesAllZero());
        }
    }

    @Test
    void exportedPayloadIsDefensivelyCopied() {
        final InMemorySerializationProcessor source =
                new InMemorySerializationProcessor();
        source.storeData(
                9_001L,
                3L,
                4L,
                exchange.core2.core.processors.journaling.ISerializationProcessor
                        .SerializedModuleType.MATCHING_ENGINE_ROUTER,
                0,
                bytes -> bytes.writeInt(42));

        final InMemorySerializationProcessor.SerializedModule module =
                source.exportSnapshot(9_001L).getFirst();
        final byte[] changed = module.data();
        changed[0] ^= 0x7f;

        final int restored = source.loadData(
                9_001L,
                exchange.core2.core.processors.journaling.ISerializationProcessor
                        .SerializedModuleType.MATCHING_ENGINE_ROUTER,
                0,
                bytes -> bytes.readInt());
        assertEquals(42, restored);
        assertTrue(module.checksum() > 0);
    }

    @Test
    void storesAndLoadsConcurrentModulesAtomically() throws Exception {
        final long snapshotId = 9_002L;
        final InMemorySerializationProcessor processor = new InMemorySerializationProcessor();
        final CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            final var matching = executor.submit(() -> {
                start.await();
                return processor.storeData(snapshotId, 7, 8,
                        exchange.core2.core.processors.journaling.ISerializationProcessor.SerializedModuleType
                                .MATCHING_ENGINE_ROUTER,
                        0, bytes -> bytes.writeInt(11));
            });
            final var risk = executor.submit(() -> {
                start.await();
                return processor.storeData(snapshotId, 6, 8,
                        exchange.core2.core.processors.journaling.ISerializationProcessor.SerializedModuleType
                                .RISK_ENGINE,
                        0, bytes -> bytes.writeInt(22));
            });
            start.countDown();
            assertTrue(matching.get());
            assertTrue(risk.get());
        }

        final List<InMemorySerializationProcessor.SerializedModule> modules =
                processor.exportSnapshot(snapshotId);
        assertEquals(2, modules.size());
        assertEquals(exchange.core2.core.processors.journaling.ISerializationProcessor.SerializedModuleType
                .RISK_ENGINE, modules.get(0).type());
        assertEquals(exchange.core2.core.processors.journaling.ISerializationProcessor.SerializedModuleType
                .MATCHING_ENGINE_ROUTER, modules.get(1).type());
        processor.removeSnapshot(snapshotId);
        assertFalse(processor.checkSnapshotExists(snapshotId, modules.get(0).type(), 0));
        assertThrows(IllegalStateException.class, () -> processor.exportSnapshot(snapshotId));
    }

    @Test
    void rejectsDuplicateImportAtomicallyAndJournalOperations() {
        final long snapshotId = 9_003L;
        final var matchingType = exchange.core2.core.processors.journaling.ISerializationProcessor
                .SerializedModuleType.MATCHING_ENGINE_ROUTER;
        final var riskType = exchange.core2.core.processors.journaling.ISerializationProcessor
                .SerializedModuleType.RISK_ENGINE;
        final InMemorySerializationProcessor processor = new InMemorySerializationProcessor();
        processor.storeData(snapshotId, 1, 1, matchingType, 0, bytes -> bytes.writeInt(1));

        final List<InMemorySerializationProcessor.SerializedModule> imported = List.of(
                new InMemorySerializationProcessor.SerializedModule(snapshotId, 1, 1, riskType, 0,
                        new byte[]{1}),
                new InMemorySerializationProcessor.SerializedModule(snapshotId, 1, 1, matchingType, 0,
                        new byte[]{2}));
        assertThrows(IllegalStateException.class, () -> processor.importSnapshot(imported));
        assertFalse(processor.checkSnapshotExists(snapshotId, riskType, 0));

        assertThrows(UnsupportedOperationException.class,
                () -> processor.writeToJournal(null, 0, false));
        assertThrows(UnsupportedOperationException.class,
                () -> processor.enableJournaling(0, null));
        assertThrows(UnsupportedOperationException.class,
                () -> processor.replayJournalStep(snapshotId, 0, 1, null));
        assertThrows(UnsupportedOperationException.class,
                () -> processor.replayJournalFull(InitialStateConfiguration.cleanStart("test"), null));
        processor.replayJournalFullAndThenEnableJouraling(
                InitialStateConfiguration.fromSnapshotOnly("test", snapshotId, 0), null);
    }

    private static void placeAsk(
            final ExchangeTestContainer container,
            final long orderId,
            final long uid,
            final long price,
            final long size) {
        container.submitCommandSync(
                ApiPlaceOrder.builder()
                        .uid(uid)
                        .orderId(orderId)
                        .price(price)
                        .size(size)
                        .action(OrderAction.ASK)
                        .orderType(OrderType.GTC)
                        .symbol(SYMBOLSPEC_ETH_XBT.symbolId)
                        .build(),
                CommandResultCode.SUCCESS);
    }

    private static SerializationConfiguration serialization(
            final InMemorySerializationProcessor processor) {
        return SerializationConfiguration.builder()
                .enableJournaling(false)
                .serializationProcessorFactory(configuration -> processor)
                .build();
    }
}
