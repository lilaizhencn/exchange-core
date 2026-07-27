package exchange.core2.core.simulation;

import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.api.dma.DmaCancelOrder;
import exchange.core2.core.common.api.dma.DmaDeliveryRequest;
import exchange.core2.core.common.api.dma.DmaFill;
import exchange.core2.core.common.api.dma.DmaLifecycleResult;
import exchange.core2.core.common.api.dma.DmaLifecycleSnapshot;
import exchange.core2.core.common.api.dma.DmaLimitOrder;
import exchange.core2.core.common.api.dma.DmaNewOrder;
import exchange.core2.core.common.api.dma.DmaOrderResult;
import exchange.core2.core.common.api.dma.DmaOrderState;
import exchange.core2.core.common.api.dma.DmaOrderStatus;
import exchange.core2.core.common.api.dma.DmaProtectedMarketOrder;
import exchange.core2.core.common.api.dma.DmaReplaceOrder;
import exchange.core2.core.common.cmd.CommandResultCode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32C;

/**
 * Atomic, versioned and checksummed persistence for the DMA lifecycle
 * projection. The native exchange snapshot and this file must share a
 * checkpoint identifier.
 */
public final class DmaLifecycleSnapshotStore {

    private static final int MAGIC = 0x444D4153;
    private static final int VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 512 * 1024 * 1024;
    private static final int MAX_COLLECTION_SIZE = 10_000_000;

    private static final byte LIMIT_ORDER = 1;
    private static final byte PROTECTED_ORDER = 2;
    private static final byte CANCEL_ORDER = 3;
    private static final byte REPLACE_ORDER = 4;

    private final Path storageDirectory;
    private final String exchangeId;
    private final String accountingMode;

    public DmaLifecycleSnapshotStore(
            final Path storageDirectory,
            final String exchangeId) {
        this(storageDirectory, exchangeId, "MATCHING_ONLY");
    }

    public DmaLifecycleSnapshotStore(
            final Path storageDirectory,
            final String exchangeId,
            final String accountingMode) {
        this.storageDirectory = Objects.requireNonNull(storageDirectory, "storageDirectory")
                .toAbsolutePath()
                .normalize();
        this.exchangeId = requireExchangeId(exchangeId);
        this.accountingMode = Objects.requireNonNull(
                accountingMode,
                "accountingMode");
    }

    public Path checkpointPath(final long checkpointId) {
        if (checkpointId <= 0) {
            throw new IllegalArgumentException("checkpointId must be positive");
        }
        return storageDirectory.resolve(
                exchangeId + "_dma_lifecycle_" + checkpointId + ".dmas");
    }

    public synchronized Path save(
            final long checkpointId,
            final DmaLifecycleSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        final byte[] payload = encode(checkpointId, snapshot);
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IOException(
                    "DMA lifecycle snapshot exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        }

        final CRC32C checksum = new CRC32C();
        checksum.update(payload, 0, payload.length);
        Files.createDirectories(storageDirectory);
        final Path target = checkpointPath(checkpointId);
        final Path temporary = Files.createTempFile(
                storageDirectory,
                target.getFileName().toString(),
                ".tmp");

        try {
            final ByteBuffer header = ByteBuffer.allocate(16);
            header.putInt(MAGIC);
            header.putInt(VERSION);
            header.putInt(payload.length);
            header.putInt((int) checksum.getValue());
            header.flip();

            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                writeFully(channel, header);
                writeFully(channel, ByteBuffer.wrap(payload));
                channel.force(true);
            }

            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(storageDirectory);
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public DmaLifecycleSnapshot load(final long checkpointId) throws IOException {
        final Path path = checkpointPath(checkpointId);
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("invalid DMA lifecycle snapshot magic");
            }
            if (input.readInt() != VERSION) {
                throw new IOException("unsupported DMA lifecycle snapshot version");
            }

            final int payloadLength = input.readInt();
            if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES) {
                throw new IOException(
                        "invalid DMA lifecycle snapshot payload length " + payloadLength);
            }
            final int expectedChecksum = input.readInt();
            final byte[] payload = input.readNBytes(payloadLength);
            if (payload.length != payloadLength || input.read() != -1) {
                throw new IOException("truncated or trailing DMA lifecycle snapshot data");
            }

            final CRC32C checksum = new CRC32C();
            checksum.update(payload, 0, payload.length);
            if ((int) checksum.getValue() != expectedChecksum) {
                throw new IOException("DMA lifecycle snapshot checksum mismatch");
            }
            return decode(checkpointId, payload);
        } catch (final EOFException truncated) {
            throw new IOException("truncated DMA lifecycle snapshot", truncated);
        }
    }

    private byte[] encode(
            final long checkpointId,
            final DmaLifecycleSnapshot snapshot) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF(exchangeId);
            output.writeUTF(accountingMode);
            output.writeLong(checkpointId);

            final List<Map.Entry<Long, DmaOrderState>> orders =
                    snapshot.orders().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .toList();
            output.writeInt(orders.size());
            for (final Map.Entry<Long, DmaOrderState> order : orders) {
                output.writeLong(order.getKey());
                writeState(output, order.getValue());
            }

            output.writeInt(snapshot.completedDeliveries().size());
            for (final DmaLifecycleSnapshot.CompletedDelivery delivery
                    : snapshot.completedDeliveries()) {
                writeRequest(output, delivery.request());
                writeLifecycleResult(output, delivery.result());
            }
        }
        return bytes.toByteArray();
    }

    private DmaLifecycleSnapshot decode(
            final long expectedCheckpointId,
            final byte[] payload) throws IOException {
        try (DataInputStream input =
                     new DataInputStream(new ByteArrayInputStream(payload))) {
            if (!exchangeId.equals(input.readUTF())) {
                throw new IOException("DMA lifecycle snapshot exchange ID mismatch");
            }
            if (!accountingMode.equals(input.readUTF())) {
                throw new IOException(
                        "DMA lifecycle snapshot accounting mode mismatch");
            }
            if (input.readLong() != expectedCheckpointId) {
                throw new IOException("DMA lifecycle snapshot checkpoint ID mismatch");
            }

            final int orderCount = readSize(input, "order");
            final Map<Long, DmaOrderState> orders =
                    new HashMap<>(mapCapacity(orderCount));
            for (int index = 0; index < orderCount; index++) {
                final long orderId = input.readLong();
                if (orders.putIfAbsent(orderId, readState(input)) != null) {
                    throw new IOException("duplicate lifecycle order " + orderId);
                }
            }

            final int deliveryCount = readSize(input, "delivery");
            final List<DmaLifecycleSnapshot.CompletedDelivery> deliveries =
                    new ArrayList<>(deliveryCount);
            for (int index = 0; index < deliveryCount; index++) {
                deliveries.add(new DmaLifecycleSnapshot.CompletedDelivery(
                        readRequest(input),
                        readLifecycleResult(input)));
            }
            if (input.available() != 0) {
                throw new IOException("trailing DMA lifecycle payload data");
            }
            return new DmaLifecycleSnapshot(orders, deliveries);
        } catch (final EOFException truncated) {
            throw new IOException("truncated DMA lifecycle payload", truncated);
        } catch (final IllegalArgumentException invalid) {
            throw new IOException("invalid DMA lifecycle payload", invalid);
        }
    }

    private static void writeState(
            final DataOutputStream output,
            final DmaOrderState state) throws IOException {
        writeNewOrder(output, state.order());
        output.writeByte(state.status().ordinal());
        output.writeLong(state.filledQuantity());
        output.writeLong(state.cancelledQuantity());
        output.writeLong(state.rejectedQuantity());
        output.writeLong(state.remainingQuantity());
        output.writeLong(state.version());
    }

    private static DmaOrderState readState(final DataInputStream input) throws IOException {
        final DmaNewOrder order = readNewOrder(input);
        final DmaOrderStatus status = enumValue(
                DmaOrderStatus.values(),
                input.readUnsignedByte(),
                "order status");
        return new DmaOrderState(
                order,
                status,
                input.readLong(),
                input.readLong(),
                input.readLong(),
                input.readLong(),
                input.readLong());
    }

    private static void writeLifecycleResult(
            final DataOutputStream output,
            final DmaLifecycleResult result) throws IOException {
        output.writeLong(result.deliveryId());
        writeCommandResult(output, result.commandResult());
        writeState(output, result.orderState());
        output.writeBoolean(result.duplicateDelivery());
    }

    private static DmaLifecycleResult readLifecycleResult(
            final DataInputStream input) throws IOException {
        return new DmaLifecycleResult(
                input.readLong(),
                readCommandResult(input),
                readState(input),
                input.readBoolean());
    }

    private static void writeCommandResult(
            final DataOutputStream output,
            final DmaOrderResult result) throws IOException {
        output.writeLong(result.orderId());
        output.writeInt(result.resultCode().getCode());
        output.writeInt(result.fills().size());
        for (final DmaFill fill : result.fills()) {
            output.writeLong(fill.makerOrderId());
            output.writeLong(fill.makerClientId());
            output.writeLong(fill.price());
            output.writeLong(fill.quantity());
            output.writeBoolean(fill.incomingOrderComplete());
            output.writeBoolean(fill.makerOrderComplete());
        }
        output.writeLong(result.cancelledQuantity());
        output.writeLong(result.rejectedQuantity());
    }

    private static DmaOrderResult readCommandResult(
            final DataInputStream input) throws IOException {
        final long orderId = input.readLong();
        final CommandResultCode resultCode = resultCode(input.readInt());
        final int fillCount = readSize(input, "fill");
        final List<DmaFill> fills = new ArrayList<>(fillCount);
        for (int index = 0; index < fillCount; index++) {
            fills.add(new DmaFill(
                    input.readLong(),
                    input.readLong(),
                    input.readLong(),
                    input.readLong(),
                    input.readBoolean(),
                    input.readBoolean()));
        }
        return new DmaOrderResult(
                orderId,
                resultCode,
                fills,
                input.readLong(),
                input.readLong());
    }

    private static void writeRequest(
            final DataOutputStream output,
            final DmaDeliveryRequest request) throws IOException {
        if (request instanceof DmaNewOrder order) {
            writeNewOrder(output, order);
        } else if (request instanceof DmaCancelOrder cancel) {
            output.writeByte(CANCEL_ORDER);
            output.writeLong(cancel.deliveryId());
            output.writeLong(cancel.orderId());
            output.writeLong(cancel.clientId());
            output.writeInt(cancel.symbol());
        } else if (request instanceof DmaReplaceOrder replace) {
            output.writeByte(REPLACE_ORDER);
            output.writeLong(replace.deliveryId());
            output.writeLong(replace.orderId());
            output.writeLong(replace.clientId());
            output.writeInt(replace.symbol());
            output.writeByte(replace.side().getCode());
            output.writeLong(replace.newPrice());
            output.writeLong(replace.newQuantity());
        } else {
            throw new IOException("unsupported DMA request " + request.getClass().getName());
        }
    }

    private static DmaDeliveryRequest readRequest(
            final DataInputStream input) throws IOException {
        final byte type = input.readByte();
        if (type == LIMIT_ORDER || type == PROTECTED_ORDER) {
            return readNewOrder(input, type);
        }
        if (type == CANCEL_ORDER) {
            return new DmaCancelOrder(
                    input.readLong(),
                    input.readLong(),
                    input.readLong(),
                    input.readInt());
        }
        if (type == REPLACE_ORDER) {
            return new DmaReplaceOrder(
                    input.readLong(),
                    input.readLong(),
                    input.readLong(),
                    input.readInt(),
                    OrderAction.of(input.readByte()),
                    input.readLong(),
                    input.readLong());
        }
        throw new IOException("unknown DMA request type " + type);
    }

    private static void writeNewOrder(
            final DataOutputStream output,
            final DmaNewOrder order) throws IOException {
        if (order instanceof DmaLimitOrder) {
            output.writeByte(LIMIT_ORDER);
        } else if (order instanceof DmaProtectedMarketOrder) {
            output.writeByte(PROTECTED_ORDER);
        } else {
            throw new IOException("unsupported DMA new order " + order.getClass().getName());
        }
        output.writeLong(order.deliveryId());
        output.writeLong(order.orderId());
        output.writeLong(order.clientId());
        output.writeInt(order.symbol());
        output.writeByte(order.side().getCode());
        output.writeLong(order.price());
        output.writeLong(order.quantity());
    }

    private static DmaNewOrder readNewOrder(
            final DataInputStream input) throws IOException {
        return readNewOrder(input, input.readByte());
    }

    private static DmaNewOrder readNewOrder(
            final DataInputStream input,
            final byte type) throws IOException {
        final long deliveryId = input.readLong();
        final long orderId = input.readLong();
        final long clientId = input.readLong();
        final int symbol = input.readInt();
        final OrderAction side = OrderAction.of(input.readByte());
        final long price = input.readLong();
        final long quantity = input.readLong();

        if (type == LIMIT_ORDER) {
            return new DmaLimitOrder(
                    deliveryId,
                    orderId,
                    clientId,
                    symbol,
                    side,
                    price,
                    quantity);
        }
        if (type == PROTECTED_ORDER) {
            return new DmaProtectedMarketOrder(
                    deliveryId,
                    orderId,
                    clientId,
                    symbol,
                    side,
                    price,
                    quantity);
        }
        throw new IOException("unknown DMA new-order type " + type);
    }

    private static int readSize(
            final DataInputStream input,
            final String collectionName) throws IOException {
        final int size = input.readInt();
        if (size < 0 || size > MAX_COLLECTION_SIZE) {
            throw new IOException("invalid " + collectionName + " count " + size);
        }
        return size;
    }

    private static int mapCapacity(final int size) {
        return size < 3
                ? size + 1
                : (int) Math.min(Integer.MAX_VALUE, size / 0.75f + 1.0f);
    }

    private static CommandResultCode resultCode(final int code) throws IOException {
        for (final CommandResultCode resultCode : CommandResultCode.values()) {
            if (resultCode.getCode() == code) {
                return resultCode;
            }
        }
        throw new IOException("unknown command result code " + code);
    }

    private static <T> T enumValue(
            final T[] values,
            final int ordinal,
            final String name) throws IOException {
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IOException("invalid " + name + " " + ordinal);
        }
        return values[ordinal];
    }

    private static void writeFully(
            final FileChannel channel,
            final ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void forceDirectory(final Path directory) throws IOException {
        try (FileChannel channel =
                     FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static String requireExchangeId(final String exchangeId) {
        Objects.requireNonNull(exchangeId, "exchangeId");
        if (!exchangeId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(
                    "exchangeId must contain only letters, digits, dot, underscore or dash");
        }
        return exchangeId;
    }
}
