package exchange.core2.core.processors.journaling;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.config.InitialStateConfiguration;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.zip.CRC32C;

/**
 * Snapshot-only serialization processor backed by caller-owned memory.
 *
 * <p>The exported module blobs are suitable for embedding in an external
 * consensus snapshot. Import them before starting an exchange configured with
 * {@link InitialStateConfiguration#fromSnapshotOnly(String, long, long)}.</p>
 */
public final class InMemorySerializationProcessor implements ISerializationProcessor {

    private static final Comparator<SerializedModule> MODULE_ORDER =
            Comparator.comparing(SerializedModule::type)
                    .thenComparingInt(SerializedModule::instanceId);

    private final Map<SnapshotKey, SerializedModule> modules = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @Override
    public boolean storeData(
            final long snapshotId,
            final long seq,
            final long timestampNs,
            final SerializedModuleType type,
            final int instanceId,
            final WriteBytesMarshallable obj) {
        validateCoordinates(snapshotId, type, instanceId);
        Objects.requireNonNull(obj, "obj");

        final SerializedModule module = new SerializedModule(
                snapshotId,
                seq,
                timestampNs,
                type,
                instanceId,
                serialize(obj));
        final SnapshotKey key = new SnapshotKey(snapshotId, type, instanceId);
        lock.writeLock().lock();
        try {
            if (modules.putIfAbsent(key, module) != null) {
                throw new IllegalStateException("Duplicate snapshot module " + key);
            }
        } finally {
            lock.writeLock().unlock();
        }
        return true;
    }

    @Override
    public <T> T loadData(
            final long snapshotId,
            final SerializedModuleType type,
            final int instanceId,
            final Function<BytesIn, T> initFunc) {
        validateCoordinates(snapshotId, type, instanceId);
        Objects.requireNonNull(initFunc, "initFunc");

        final SnapshotKey key = new SnapshotKey(snapshotId, type, instanceId);
        final SerializedModule module;
        lock.readLock().lock();
        try {
            final SerializedModule stored = modules.get(key);
            module = stored == null ? null : stored.copy();
        } finally {
            lock.readLock().unlock();
        }
        if (module == null) {
            throw new IllegalStateException("Snapshot module not found " + key);
        }
        module.verifyChecksum();

        final Bytes<ByteBuffer> bytes = Bytes.wrapForRead(ByteBuffer.wrap(module.data()));
        try {
            final T restored = initFunc.apply(bytes);
            if (bytes.readRemaining() != 0) {
                throw new IllegalStateException(
                        "Snapshot module has " + bytes.readRemaining()
                                + " unread bytes " + key);
            }
            return restored;
        } finally {
            bytes.releaseLast();
        }
    }

    public List<SerializedModule> exportSnapshot(final long snapshotId) {
        if (snapshotId <= 0) {
            throw new IllegalArgumentException("snapshotId must be positive");
        }
        final List<SerializedModule> exported = new ArrayList<>();
        lock.readLock().lock();
        try {
            modules.forEach((key, module) -> {
                if (key.snapshotId == snapshotId) {
                    exported.add(module.copy());
                }
            });
        } finally {
            lock.readLock().unlock();
        }
        if (exported.isEmpty()) {
            throw new IllegalStateException("Snapshot " + snapshotId + " not found");
        }
        exported.sort(MODULE_ORDER);
        return List.copyOf(exported);
    }

    public void importSnapshot(final Collection<SerializedModule> importedModules) {
        Objects.requireNonNull(importedModules, "importedModules");
        if (importedModules.isEmpty()) {
            throw new IllegalArgumentException("Snapshot modules must not be empty");
        }
        final Map<SnapshotKey, SerializedModule> validated = new HashMap<>();
        for (final SerializedModule imported : importedModules) {
            Objects.requireNonNull(imported, "snapshot module");
            validateCoordinates(
                    imported.snapshotId(), imported.type(), imported.instanceId());
            final SerializedModule module = imported.copy();
            module.verifyChecksum();
            final SnapshotKey key = new SnapshotKey(
                    module.snapshotId(), module.type(), module.instanceId());
            if (validated.putIfAbsent(key, module) != null) {
                throw new IllegalArgumentException("Duplicate imported snapshot module " + key);
            }
        }
        lock.writeLock().lock();
        try {
            for (final SnapshotKey key : validated.keySet()) {
                if (modules.containsKey(key)) {
                    throw new IllegalStateException("Snapshot module already exists " + key);
                }
            }
            modules.putAll(validated);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeSnapshot(final long snapshotId) {
        if (snapshotId <= 0) {
            throw new IllegalArgumentException("snapshotId must be positive");
        }
        lock.writeLock().lock();
        try {
            modules.keySet().removeIf(key -> key.snapshotId == snapshotId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void writeToJournal(final OrderCommand cmd, final long dSeq, final boolean eob)
            throws IOException {
        throw new UnsupportedOperationException("In-memory snapshots do not support journaling");
    }

    @Override
    public void enableJournaling(final long afterSeq, final ExchangeApi api) {
        throw new UnsupportedOperationException("In-memory snapshots do not support journaling");
    }

    @Override
    public NavigableMap<Long, SnapshotDescriptor> findAllSnapshotPoints() {
        return Collections.emptyNavigableMap();
    }

    @Override
    public void replayJournalStep(
            final long snapshotId,
            final long seqFrom,
            final long seqTo,
            final ExchangeApi api) {
        throw new UnsupportedOperationException("In-memory snapshots do not support journaling");
    }

    @Override
    public long replayJournalFull(
            final InitialStateConfiguration initialStateConfiguration,
            final ExchangeApi api) {
        throw new UnsupportedOperationException("In-memory snapshots do not support journaling");
    }

    @Override
    public void replayJournalFullAndThenEnableJouraling(
            final InitialStateConfiguration initialStateConfiguration,
            final ExchangeApi exchangeApi) {
        if (initialStateConfiguration.getJournalTimestampNs() != 0) {
            throw new UnsupportedOperationException(
                    "In-memory snapshots do not support journal replay");
        }
    }

    @Override
    public boolean checkSnapshotExists(
            final long snapshotId,
            final SerializedModuleType type,
            final int instanceId) {
        lock.readLock().lock();
        try {
            return modules.containsKey(new SnapshotKey(snapshotId, type, instanceId));
        } finally {
            lock.readLock().unlock();
        }
    }

    private static byte[] serialize(final WriteBytesMarshallable obj) {
        final Bytes<ByteBuffer> bytes = Bytes.elasticHeapByteBuffer(128);
        try {
            obj.writeMarshallable(bytes);
            final int length = Math.toIntExact(bytes.writePosition());
            final byte[] data = new byte[length];
            bytes.readPosition(0);
            bytes.read(data);
            return data;
        } finally {
            bytes.releaseLast();
        }
    }

    private static void validateCoordinates(
            final long snapshotId,
            final SerializedModuleType type,
            final int instanceId) {
        if (snapshotId <= 0) {
            throw new IllegalArgumentException("snapshotId must be positive");
        }
        Objects.requireNonNull(type, "type");
        if (instanceId < 0) {
            throw new IllegalArgumentException("instanceId must not be negative");
        }
    }

    private record SnapshotKey(
            long snapshotId,
            SerializedModuleType type,
            int instanceId) {
    }

    public static final class SerializedModule {
        private final long snapshotId;
        private final long sequence;
        private final long timestampNs;
        private final SerializedModuleType type;
        private final int instanceId;
        private final byte[] data;
        private final long checksum;

        public SerializedModule(
                final long snapshotId,
                final long sequence,
                final long timestampNs,
                final SerializedModuleType type,
                final int instanceId,
                final byte[] data) {
            validateCoordinates(snapshotId, type, instanceId);
            this.snapshotId = snapshotId;
            this.sequence = sequence;
            this.timestampNs = timestampNs;
            this.type = type;
            this.instanceId = instanceId;
            this.data = Objects.requireNonNull(data, "data").clone();
            this.checksum = checksum(this.data);
        }

        public long snapshotId() {
            return snapshotId;
        }

        public long sequence() {
            return sequence;
        }

        public long timestampNs() {
            return timestampNs;
        }

        public SerializedModuleType type() {
            return type;
        }

        public int instanceId() {
            return instanceId;
        }

        public byte[] data() {
            return data.clone();
        }

        public long checksum() {
            return checksum;
        }

        private void verifyChecksum() {
            if (checksum != checksum(data)) {
                throw new IllegalStateException("Snapshot module checksum mismatch");
            }
        }

        private SerializedModule copy() {
            return new SerializedModule(
                    snapshotId, sequence, timestampNs, type, instanceId, data);
        }

        @Override
        public boolean equals(final Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SerializedModule that)) {
                return false;
            }
            return snapshotId == that.snapshotId
                    && sequence == that.sequence
                    && timestampNs == that.timestampNs
                    && instanceId == that.instanceId
                    && type == that.type
                    && Arrays.equals(data, that.data);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(snapshotId, sequence, timestampNs, type, instanceId);
            result = 31 * result + Arrays.hashCode(data);
            return result;
        }

        private static long checksum(final byte[] data) {
            final CRC32C checksum = new CRC32C();
            checksum.update(data, 0, data.length);
            return checksum.getValue();
        }
    }
}
