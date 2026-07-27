package exchange.core2.core.simulation;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.binary.BatchAddSymbolsCommand;
import exchange.core2.core.common.api.dma.DmaCancelOrder;
import exchange.core2.core.common.api.dma.DmaLifecycleResult;
import exchange.core2.core.common.api.dma.DmaLifecycleSnapshot;
import exchange.core2.core.common.api.dma.DmaLimitOrder;
import exchange.core2.core.common.api.dma.DmaOrderState;
import exchange.core2.core.common.api.dma.DmaProtectedMarketOrder;
import exchange.core2.core.common.api.dma.DmaReplaceOrder;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.LoggingConfiguration;
import exchange.core2.core.common.config.OrdersProcessingConfiguration;
import exchange.core2.core.common.config.ReportsQueriesConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import exchange.core2.core.processors.journaling.DiskSerializationProcessor;
import exchange.core2.core.processors.journaling.DiskSerializationProcessorConfiguration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Durable MATCHING_ONLY simulation with observable order operations and
 * deterministic symbol-partition publication.
 */
public final class ProductionSimulation implements AutoCloseable {

    private static final OrdersProcessingConfiguration MATCHING_ONLY =
            OrdersProcessingConfiguration.builder()
                    .riskProcessingMode(
                            OrdersProcessingConfiguration.RiskProcessingMode.MATCHING_ONLY)
                    .marginTradingMode(
                            OrdersProcessingConfiguration.MarginTradingMode.MARGIN_TRADING_DISABLED)
                    .build();

    private final ProductionSimulationConfiguration configuration;
    private final ExchangeCore exchangeCore;
    private final ExchangeApi exchangeApi;
    private final SymbolPartitionDispatcher dispatcher;
    private final ProductionSimulationMetrics metrics = new ProductionSimulationMetrics();
    private final DmaLifecycleSnapshotStore lifecycleStore;
    private final ReentrantReadWriteLock checkpointLock = new ReentrantReadWriteLock();
    private final AtomicBoolean closed = new AtomicBoolean();

    private ProductionSimulation(
            final ProductionSimulationConfiguration configuration,
            final Long recoveryCheckpointId) throws IOException {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        lifecycleStore = new DmaLifecycleSnapshotStore(
                configuration.storageDirectory(),
                configuration.exchangeId());

        final DmaLifecycleSnapshot recoveredLifecycle = recoveryCheckpointId == null
                ? null
                : lifecycleStore.load(recoveryCheckpointId);
        final InitialStateConfiguration initialState = recoveryCheckpointId == null
                ? InitialStateConfiguration.cleanStart(configuration.exchangeId())
                : InitialStateConfiguration.fromSnapshotOnly(
                        configuration.exchangeId(),
                        recoveryCheckpointId,
                        0L);

        exchangeCore = ExchangeCore.builder()
                .resultsConsumer((command, sequence) -> {
                })
                .exchangeConfiguration(ExchangeConfiguration.defaultBuilder()
                        .ordersProcessingCfg(MATCHING_ONLY)
                        .performanceCfg(configuration.performanceConfiguration())
                        .initStateCfg(initialState)
                        .reportsQueriesCfg(ReportsQueriesConfiguration.createStandardConfig())
                        .loggingCfg(LoggingConfiguration.DEFAULT)
                        .serializationCfg(
                                snapshotSerialization(configuration.storageDirectory()))
                        .build())
                .build();

        boolean started = false;
        try {
            exchangeCore.startup();
            started = true;
            exchangeApi = exchangeCore.getApi();
            if (recoveredLifecycle != null) {
                exchangeApi.recoverDmaLifecycle(recoveredLifecycle);
            }
            dispatcher = new SymbolPartitionDispatcher(configuration.symbolPartitions());
        } catch (final RuntimeException error) {
            if (started) {
                exchangeCore.shutdown();
            }
            throw error;
        }
    }

    public static ProductionSimulation start(
            final ProductionSimulationConfiguration configuration) throws IOException {
        return new ProductionSimulation(configuration, null);
    }

    public static ProductionSimulation recover(
            final ProductionSimulationConfiguration configuration,
            final long checkpointId) throws IOException {
        if (checkpointId <= 0) {
            throw new IllegalArgumentException("checkpointId must be positive");
        }
        return new ProductionSimulation(configuration, checkpointId);
    }

    public void addSymbols(final Collection<CoreSymbolSpecification> symbols) {
        Objects.requireNonNull(symbols, "symbols");
        checkpointLock.writeLock().lock();
        try {
            requireOpen();
            dispatcher.publicationBarrier().join();
            final CommandResultCode result =
                    exchangeApi.submitBinaryDataAsync(new BatchAddSymbolsCommand(symbols)).join();
            if (result != CommandResultCode.SUCCESS) {
                throw new IllegalStateException(
                        "could not add simulation symbols: " + result);
            }
        } finally {
            checkpointLock.writeLock().unlock();
        }
    }

    public CompletableFuture<ProductionSimulationResult> submit(
            final DmaLimitOrder order) {
        Objects.requireNonNull(order, "order");
        return execute(
                SimulationOperation.SUBMIT_LIMIT,
                order.symbol(),
                () -> exchangeApi.dmaLifecycle().submit(order));
    }

    public CompletableFuture<ProductionSimulationResult> submitProtected(
            final DmaProtectedMarketOrder order) {
        Objects.requireNonNull(order, "order");
        return execute(
                SimulationOperation.SUBMIT_PROTECTED,
                order.symbol(),
                () -> exchangeApi.dmaLifecycle().submitProtected(order));
    }

    public CompletableFuture<ProductionSimulationResult> replace(
            final DmaReplaceOrder replacement) {
        Objects.requireNonNull(replacement, "replacement");
        return execute(
                SimulationOperation.REPLACE,
                replacement.symbol(),
                () -> exchangeApi.dmaLifecycle().replace(replacement));
    }

    public CompletableFuture<ProductionSimulationResult> cancel(
            final DmaCancelOrder cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        return execute(
                SimulationOperation.CANCEL,
                cancellation.symbol(),
                () -> exchangeApi.dmaLifecycle().cancel(cancellation));
    }

    /**
     * Persists native matching shards first, then atomically publishes the DMA
     * lifecycle file as the checkpoint commit marker.
     */
    public ProductionSimulationCheckpoint checkpoint(
            final long checkpointId) throws IOException {
        if (checkpointId <= 0) {
            throw new IllegalArgumentException("checkpointId must be positive");
        }

        final long startedNanos = metrics.start(SimulationOperation.CHECKPOINT);
        checkpointLock.writeLock().lock();
        try {
            requireOpen();
            dispatcher.publicationBarrier().join();
            final CommandResultCode result = exchangeApi.submitCommandAsync(
                    ApiPersistState.builder().dumpId(checkpointId).build()).join();
            if (result != CommandResultCode.SUCCESS) {
                throw new IOException("native exchange checkpoint failed: " + result);
            }

            final Path lifecyclePath =
                    lifecycleStore.save(
                            checkpointId,
                            exchangeApi.dmaLifecycle().snapshot());
            metrics.success(SimulationOperation.CHECKPOINT, startedNanos);
            return new ProductionSimulationCheckpoint(checkpointId, lifecyclePath);
        } catch (final IOException | RuntimeException error) {
            metrics.failure(SimulationOperation.CHECKPOINT, startedNanos);
            throw error;
        } finally {
            checkpointLock.writeLock().unlock();
        }
    }

    public DmaOrderState getOrder(final long orderId) {
        requireOpen();
        return exchangeApi.dmaLifecycle().getOrder(orderId);
    }

    /**
     * Drains publication lanes and returns an order-book view at that boundary.
     */
    public L2MarketData orderBook(final int symbol) {
        checkpointLock.writeLock().lock();
        try {
            requireOpen();
            dispatcher.publicationBarrier().join();
            return exchangeApi.requestOrderBookAsync(symbol, -1).join();
        } finally {
            checkpointLock.writeLock().unlock();
        }
    }

    public int partitionFor(final int symbol) {
        return dispatcher.partitionFor(symbol);
    }

    public ProductionSimulationMetrics.Snapshot metrics() {
        return metrics.snapshot();
    }

    public ProductionSimulationConfiguration configuration() {
        return configuration;
    }

    private CompletableFuture<ProductionSimulationResult> execute(
            final SimulationOperation operation,
            final int symbol,
            final Supplier<CompletableFuture<DmaLifecycleResult>> task) {
        final long startedNanos = metrics.start(operation);
        checkpointLock.readLock().lock();
        try {
            requireOpen();
            final CompletableFuture<
                    SymbolPartitionDispatcher.PartitionResult<DmaLifecycleResult>>
                    partitionResult = dispatcher.submit(symbol, task);
            final CompletableFuture<ProductionSimulationResult> result =
                    new CompletableFuture<>();
            partitionResult.whenComplete((completed, error) -> {
                if (error == null) {
                    metrics.success(operation, startedNanos, completed.value());
                    result.complete(new ProductionSimulationResult(
                            operation,
                            completed.partition(),
                            completed.partitionSequence(),
                            completed.value()));
                } else {
                    metrics.failure(operation, startedNanos);
                    result.completeExceptionally(unwrap(error));
                }
            });
            return result;
        } catch (final RuntimeException error) {
            metrics.failure(operation, startedNanos);
            throw error;
        } finally {
            checkpointLock.readLock().unlock();
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("production simulation is closed");
        }
    }

    @Override
    public void close() {
        checkpointLock.writeLock().lock();
        try {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            dispatcher.publicationBarrier().join();
            dispatcher.close();
            exchangeCore.shutdown(5, TimeUnit.SECONDS);
        } finally {
            checkpointLock.writeLock().unlock();
        }
    }

    private static SerializationConfiguration snapshotSerialization(
            final Path storageDirectory) {
        final DiskSerializationProcessorConfiguration defaults =
                DiskSerializationProcessorConfiguration.createDefaultConfig();
        final DiskSerializationProcessorConfiguration disk =
                DiskSerializationProcessorConfiguration.builder()
                        .storageFolder(storageDirectory.toString())
                        .snapshotLz4CompressorFactory(
                                defaults.getSnapshotLz4CompressorFactory())
                        .journalFileMaxSize(defaults.getJournalFileMaxSize())
                        .journalBufferSize(defaults.getJournalBufferSize())
                        .journalBatchCompressThreshold(
                                defaults.getJournalBatchCompressThreshold())
                        .journalLz4CompressorFactory(
                                defaults.getJournalLz4CompressorFactory())
                        .build();

        return SerializationConfiguration.builder()
                .enableJournaling(false)
                .serializationProcessorFactory(
                        exchangeConfiguration ->
                                new DiskSerializationProcessor(
                                        exchangeConfiguration,
                                        disk))
                .build();
    }

    private static Throwable unwrap(final Throwable error) {
        return error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
    }
}
