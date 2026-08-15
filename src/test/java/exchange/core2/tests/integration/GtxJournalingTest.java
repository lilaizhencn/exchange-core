package exchange.core2.tests.integration;

import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import exchange.core2.core.processors.journaling.DiskSerializationProcessor;
import exchange.core2.core.processors.journaling.DiskSerializationProcessorConfiguration;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static exchange.core2.tests.util.TestConstants.SYMBOLSPEC_ETH_XBT;
import static exchange.core2.tests.util.TestConstants.UID_1;
import static exchange.core2.tests.util.TestConstants.UID_2;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GtxJournalingTest {

    @TempDir
    Path storageDirectory;

    @Test
    @Timeout(30)
    void shouldReplayGtxJournalEntry() throws Exception {
        final String exchangeId = "GTX_JOURNAL";
        final long snapshotId = 7_001L;
        final SerializationConfiguration serialization = serializationConfiguration();
        final int symbol = SYMBOLSPEC_ETH_XBT.symbolId;
        final int originalStateHash;

        try (ExchangeTestContainer container = ExchangeTestContainer.create(
                PerformanceConfiguration.DEFAULT,
                InitialStateConfiguration.cleanStartJournaling(exchangeId),
                serialization)) {
            container.initBasicSymbols();
            container.initBasicUsers();

            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(UID_1)
                            .orderId(401)
                            .price(1_600)
                            .size(7)
                            .action(OrderAction.ASK)
                            .orderType(OrderType.GTC)
                            .symbol(symbol)
                            .build(),
                    CommandResultCode.SUCCESS);
            assertEquals(
                    CommandResultCode.SUCCESS,
                    container.getApi().submitCommandAsync(
                            ApiPersistState.builder().dumpId(snapshotId).build()).join());

            container.submitCommandSync(
                    ApiPlaceOrder.builder()
                            .uid(UID_2)
                            .orderId(402)
                            .price(1_700)
                            .reservePrice(1_800)
                            .size(2)
                            .action(OrderAction.BID)
                            .orderType(OrderType.GTX)
                            .symbol(symbol)
                            .build(),
                    cmd -> {
                        assertThat(cmd.resultCode, is(CommandResultCode.MATCHING_POST_ONLY_FAILED));
                        assertNotNull(cmd.matcherEvent);
                        assertThat(cmd.matcherEvent.eventType, is(MatcherEventType.REJECT));
                    });

            originalStateHash = container.requestStateHash();
        }

        try (ExchangeTestContainer recovered = ExchangeTestContainer.create(
                PerformanceConfiguration.DEFAULT,
                InitialStateConfiguration.lastKnownStateFromJournal(exchangeId, snapshotId, 0),
                serialization)) {
            assertEquals(originalStateHash, recovered.requestStateHash());
            assertEquals(7, recovered.requestCurrentOrderBook(symbol).askVolumes[0]);
            assertEquals(0, recovered.requestCurrentOrderBook(symbol).bidSize);
            assertTrue(recovered.totalBalanceReport().isGlobalBalancesAllZero());
        }
    }

    private SerializationConfiguration serializationConfiguration() {
        final DiskSerializationProcessorConfiguration defaults =
                DiskSerializationProcessorConfiguration.createDefaultConfig();
        final DiskSerializationProcessorConfiguration disk =
                DiskSerializationProcessorConfiguration.builder()
                        .storageFolder(storageDirectory.toString())
                        .snapshotLz4CompressorFactory(defaults.getSnapshotLz4CompressorFactory())
                        .journalFileMaxSize(defaults.getJournalFileMaxSize())
                        .journalBufferSize(defaults.getJournalBufferSize())
                        .journalBatchCompressThreshold(defaults.getJournalBatchCompressThreshold())
                        .journalLz4CompressorFactory(defaults.getJournalLz4CompressorFactory())
                        .build();

        return SerializationConfiguration.builder()
                .enableJournaling(true)
                .serializationProcessorFactory(exchangeConfiguration ->
                        new DiskSerializationProcessor(exchangeConfiguration, disk))
                .build();
    }
}
