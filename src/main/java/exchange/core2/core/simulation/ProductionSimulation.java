package exchange.core2.core.simulation;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiAddUser;
import exchange.core2.core.common.api.ApiAdjustUserBalance;
import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.binary.BatchAddSymbolsCommand;
import exchange.core2.core.common.api.dma.DmaCancelOrder;
import exchange.core2.core.common.api.dma.DmaLifecycleResult;
import exchange.core2.core.common.api.dma.DmaLifecycleSnapshot;
import exchange.core2.core.common.api.dma.DmaLimitOrder;
import exchange.core2.core.common.api.dma.DmaOrderState;
import exchange.core2.core.common.api.dma.DmaProtectedMarketOrder;
import exchange.core2.core.common.api.dma.DmaReplaceOrder;
import exchange.core2.core.common.api.reports.SingleUserReportQuery;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.LoggingConfiguration;
import exchange.core2.core.common.config.ReportsQueriesConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import exchange.core2.core.processors.journaling.DiskSerializationProcessor;
import exchange.core2.core.processors.journaling.DiskSerializationProcessorConfiguration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Durable simulation with observable order operations, deterministic
 * symbol-partition publication, and optional fully funded equity accounting.
 */
public final class ProductionSimulation implements AutoCloseable {

    private final ProductionSimulationConfiguration configuration;
    private final ProductionSimulationAccounting accounting;
    private final ExchangeCore exchangeCore;
    private final ExchangeApi exchangeApi;
    private final SymbolPartitionDispatcher dispatcher;
    private final ProductionSimulationMetrics metrics = new ProductionSimulationMetrics();
    private final DmaLifecycleSnapshotStore lifecycleStore;
    private final ReentrantReadWriteLock checkpointLock = new ReentrantReadWriteLock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger reportTransferId = new AtomicInteger(1);

    private ProductionSimulation(
            final ProductionSimulationConfiguration configuration,
            final ProductionSimulationAccounting accounting,
            final Long recoveryCheckpointId) throws IOException {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.accounting = Objects.requireNonNull(accounting, "accounting");
        lifecycleStore = new DmaLifecycleSnapshotStore(
                configuration.storageDirectory(),
                configuration.exchangeId(),
                accounting.mode().name());

        final DmaLifecycleSnapshot recoveredLifecycle = recoveryCheckpointId == null
                ? null
                : lifecycleStore.load(recoveryCheckpointId);
        // With journaling on, recovery must replay the journal on top of the
        // snapshot: the snapshot is now periodic rather than per-command, so
        // everything accepted since the last one lives only in the journal.
        // fromSnapshotOnly would silently discard exactly that window.
        final boolean journaling = configuration.journalingEnabled();
        final InitialStateConfiguration initialState;
        if (recoveryCheckpointId == null) {
            initialState = journaling
                    ? InitialStateConfiguration.cleanStartJournaling(configuration.exchangeId())
                    : InitialStateConfiguration.cleanStart(configuration.exchangeId());
        } else {
            initialState = journaling
                    ? InitialStateConfiguration.lastKnownStateFromJournal(
                            configuration.exchangeId(), recoveryCheckpointId, 0L)
                    : InitialStateConfiguration.fromSnapshotOnly(
                            configuration.exchangeId(), recoveryCheckpointId, 0L);
        }

        exchangeCore = ExchangeCore.builder()
                .resultsConsumer((command, sequence) -> {
                })
                .exchangeConfiguration(ExchangeConfiguration.defaultBuilder()
                        .ordersProcessingCfg(
                                accounting.ordersProcessingConfiguration())
                        .performanceCfg(configuration.performanceConfiguration())
                        .initStateCfg(initialState)
                        .reportsQueriesCfg(ReportsQueriesConfiguration.createStandardConfig())
                        .loggingCfg(LoggingConfiguration.DEFAULT)
                        .serializationCfg(
                                snapshotSerialization(
                                        configuration.storageDirectory(), journaling))
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
        return start(
                configuration,
                ProductionSimulationAccounting.matchingOnly());
    }

    public static ProductionSimulation start(
            final ProductionSimulationConfiguration configuration,
            final ProductionSimulationAccounting accounting) throws IOException {
        return new ProductionSimulation(configuration, accounting, null);
    }

    public static ProductionSimulation recover(
            final ProductionSimulationConfiguration configuration,
            final long checkpointId) throws IOException {
        return recover(
                configuration,
                checkpointId,
                ProductionSimulationAccounting.matchingOnly());
    }

    public static ProductionSimulation recover(
            final ProductionSimulationConfiguration configuration,
            final long checkpointId,
            final ProductionSimulationAccounting accounting) throws IOException {
        if (checkpointId <= 0) {
            throw new IllegalArgumentException("checkpointId must be positive");
        }
        return new ProductionSimulation(
                configuration,
                accounting,
                checkpointId);
    }

    public void addSymbols(final Collection<CoreSymbolSpecification> symbols) {
        Objects.requireNonNull(symbols, "symbols");
        if (accounting.isFullEquityRisk()
                && symbols.stream().anyMatch(
                        symbol -> symbol.getType() != SymbolType.EQUITY)) {
            throw new IllegalArgumentException(
                    "FULL_EQUITY_RISK accepts only EQUITY symbols");
        }
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
     * Replaces the lifecycle projection with one rebuilt from an external
     * source of truth.
     *
     * <p>Exists because the journal restores the matching engine but not the
     * lifecycle: the lifecycle lives on the API side and replay drives the
     * disruptor, so after a journalled recovery the book holds orders the
     * lifecycle has no record of. A caller that persists order state elsewhere
     * can close that gap by rebuilding the projection and applying it here.
     *
     * <p>This <strong>replaces</strong> the projection rather than merging, so
     * it must be applied before any command is accepted. Anything already
     * entered is discarded.
     */
    public void recoverLifecycle(final DmaLifecycleSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        checkpointLock.writeLock().lock();
        try {
            requireOpen();
            exchangeApi.recoverDmaLifecycle(snapshot);
        } finally {
            checkpointLock.writeLock().unlock();
        }
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

    public ProductionSimulationAccounting accounting() {
        return accounting;
    }

    /**
     * Imports one client exactly once from the configured Emporia portfolio
     * gateway. Assets are funded in ascending asset-ID order using consecutive
     * transaction IDs from the seed.
     */
    public CompletableFuture<EmporiaPortfolioSnapshot> onboardPortfolio(
            final long clientId) {
        requireFullEquityRisk();
        requireOpen();
        return Objects.requireNonNull(
                        accounting.portfolioGateway().load(clientId),
                        "portfolio load future")
                .thenApply(seed -> importPortfolio(clientId, seed));
    }

    /**
     * Reads the available risk-engine balances at a command boundary.
     *
     * <p>Skips the matching engine's per-order scan
     * ({@code includeOpenOrders=false}): {@link #toPortfolioSnapshot} only
     * reads risk-engine accounts, never the order list, so scanning every
     * resting order in the book here would cost O(book size) per command for
     * a result nobody reads.
     */
    public CompletableFuture<EmporiaPortfolioSnapshot> portfolioSnapshot(
            final long clientId,
            final long deliveryId) {
        return portfolioSnapshot(clientId, deliveryId,
                EmporiaPortfolioChange.SETTLED);
    }

    /**
     * As {@link #portfolioSnapshot(long, long)}, but states what kind of change
     * produced the snapshot so the delivery path knows whether an undelivered
     * older one may be superseded by it.
     */
    public CompletableFuture<EmporiaPortfolioSnapshot> portfolioSnapshot(
            final long clientId,
            final long deliveryId,
            final EmporiaPortfolioChange change) {
        requireFullEquityRisk();
        requireOpen();
        if (clientId <= 0) {
            throw new IllegalArgumentException("clientId must be positive");
        }
        if (deliveryId < 0) {
            throw new IllegalArgumentException(
                    "deliveryId must not be negative");
        }
        Objects.requireNonNull(change, "change");

        return exchangeApi.processReport(
                        new SingleUserReportQuery(clientId, false),
                        nextReportTransferId())
                .thenApply(report ->
                        toPortfolioSnapshot(deliveryId, clientId, report, change));
    }

    /**
     * Reads the matching-engine order IDs currently resting for a client, for
     * reconciliation against an external order-lifecycle record. Available
     * regardless of accounting mode: unlike {@link #portfolioSnapshot}, this
     * reads the matching engine's own order index, not risk-engine balances.
     */
    public CompletableFuture<Set<Long>> openOrderIds(final long clientId) {
        requireOpen();
        if (clientId <= 0) {
            throw new IllegalArgumentException("clientId must be positive");
        }

        return exchangeApi.processReport(
                        new SingleUserReportQuery(clientId),
                        nextReportTransferId())
                .thenApply(report -> report.fetchIndexedOrders().keySet());
    }

    /**
     * Applies an idempotent funding or withdrawal transaction, then publishes
     * the resulting available balances to Emporia. Retrying the same
     * transaction ID is safe when the first portfolio publication failed.
     */
    public CompletableFuture<EmporiaPortfolioSnapshot> adjustPortfolioBalance(
            final long clientId,
            final int assetId,
            final long amount,
            final long transactionId,
            final long deliveryId) {
        requireFullEquityRisk();
        if (clientId <= 0) {
            throw new IllegalArgumentException("clientId must be positive");
        }
        if (assetId < 0) {
            throw new IllegalArgumentException(
                    "assetId must not be negative");
        }
        if (transactionId <= 0) {
            throw new IllegalArgumentException(
                    "transactionId must be positive");
        }
        if (deliveryId <= 0) {
            throw new IllegalArgumentException(
                    "deliveryId must be positive");
        }

        final EmporiaPortfolioSnapshot snapshot;
        checkpointLock.writeLock().lock();
        try {
            requireOpen();
            dispatcher.publicationBarrier().join();
            final CommandResultCode result = exchangeApi.submitCommandAsync(
                    ApiAdjustUserBalance.builder()
                            .uid(clientId)
                            .currency(assetId)
                            .amount(amount)
                            .transactionId(transactionId)
                            .build())
                    .join();
            if (result != CommandResultCode.SUCCESS
                    && result
                    != CommandResultCode
                            .USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME) {
                throw new IllegalStateException(
                        "adjust portfolio balance failed: " + result);
            }
            snapshot = portfolioSnapshot(clientId, deliveryId).join();
        } finally {
            checkpointLock.writeLock().unlock();
        }

        return Objects.requireNonNull(
                        accounting.portfolioGateway().publish(snapshot),
                        "portfolio publish future")
                .thenApply(ignored -> snapshot);
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
                if (error != null) {
                    metrics.failure(operation, startedNanos);
                    result.completeExceptionally(unwrap(error));
                    return;
                }

                try {
                    synchronizePortfolios(completed.value())
                            .whenComplete((ignored, synchronizationError) -> {
                                if (synchronizationError == null) {
                                    metrics.success(
                                            operation,
                                            startedNanos,
                                            completed.value());
                                    result.complete(
                                            new ProductionSimulationResult(
                                                    operation,
                                                    completed.partition(),
                                                    completed.partitionSequence(),
                                                    completed.value()));
                                } else {
                                    metrics.portfolioFailure(
                                            operation,
                                            startedNanos,
                                            completed.value());
                                    result.completeExceptionally(
                                            unwrap(synchronizationError));
                                }
                            });
                } catch (final RuntimeException synchronizationError) {
                    metrics.portfolioFailure(
                            operation,
                            startedNanos,
                            completed.value());
                    result.completeExceptionally(synchronizationError);
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

    private void requireFullEquityRisk() {
        if (!accounting.isFullEquityRisk()) {
            throw new IllegalStateException(
                    "portfolio integration requires FULL_EQUITY_RISK");
        }
    }

    private EmporiaPortfolioSnapshot importPortfolio(
            final long requestedClientId,
            final EmporiaPortfolioSeed seed) {
        Objects.requireNonNull(seed, "portfolio seed");
        if (seed.clientId() != requestedClientId) {
            throw new IllegalArgumentException(
                    "portfolio seed client ID does not match request");
        }

        checkpointLock.writeLock().lock();
        try {
            requireOpen();
            dispatcher.publicationBarrier().join();
            requireCommandSuccess(
                    exchangeApi.submitCommandAsync(
                            ApiAddUser.builder()
                                    .uid(seed.clientId())
                                    .build())
                            .join(),
                    "create portfolio client");

            final List<Map.Entry<Integer, Long>> balances =
                    seed.balances().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .toList();
            for (int index = 0; index < balances.size(); index++) {
                final Map.Entry<Integer, Long> balance = balances.get(index);
                requireCommandSuccess(
                        exchangeApi.submitCommandAsync(
                                ApiAdjustUserBalance.builder()
                                        .uid(seed.clientId())
                                        .currency(balance.getKey())
                                        .amount(balance.getValue())
                                        .transactionId(Math.addExact(
                                                seed.firstTransactionId(),
                                                index))
                                        .build())
                                .join(),
                        "fund portfolio client");
            }

            return portfolioSnapshot(seed.clientId(), 0).join();
        } finally {
            checkpointLock.writeLock().unlock();
        }
    }

    private CompletableFuture<Void> synchronizePortfolios(
            final DmaLifecycleResult lifecycleResult) {
        if (!accounting.isFullEquityRisk()) {
            return CompletableFuture.completedFuture(null);
        }

        // A command that traded settled something for everyone it touched, and
        // each of those has to be delivered and acknowledged on its own. A
        // command that only rested or cancelled moved a margin reservation for
        // the one client who issued it: that changes what they can still spend,
        // so a live view needs it, but only the newest one carries information
        // and an undelivered older one may be superseded on the way out.
        //
        // Publishing every command as if it settled is what let the outbox grow
        // faster than it could drain: order flow far exceeds trade flow, so the
        // reservations alone outran delivery and the backlog never recovered.
        final boolean traded = !lifecycleResult.commandResult().fills().isEmpty();
        final EmporiaPortfolioChange change = traded
                ? EmporiaPortfolioChange.SETTLED
                : EmporiaPortfolioChange.RESERVED;

        final Set<Long> affectedClients = new TreeSet<>();
        affectedClients.add(lifecycleResult.orderState().order().clientId());
        lifecycleResult.commandResult().fills().forEach(
                fill -> affectedClients.add(fill.makerClientId()));

        final List<CompletableFuture<Void>> publications =
                new ArrayList<>(affectedClients.size());
        for (final long clientId : affectedClients) {
            publications.add(portfolioSnapshot(
                            clientId,
                            lifecycleResult.deliveryId(),
                            change)
                    .thenCompose(snapshot -> Objects.requireNonNull(
                            accounting.portfolioGateway().publish(snapshot),
                            "portfolio publish future")));
        }
        return CompletableFuture.allOf(
                publications.toArray(CompletableFuture[]::new));
    }

    private int nextReportTransferId() {
        return reportTransferId.getAndUpdate(
                current -> current == Integer.MAX_VALUE ? 1 : current + 1);
    }

    private static EmporiaPortfolioSnapshot toPortfolioSnapshot(
            final long deliveryId,
            final long clientId,
            final SingleUserReportResult report,
            final EmporiaPortfolioChange change) {
        if (report.getQueryExecutionStatus()
                != SingleUserReportResult.QueryExecutionStatus.OK
                || report.getAccounts() == null) {
            throw new IllegalStateException(
                    "portfolio client " + clientId + " was not found");
        }

        final Map<Integer, Long> balances = new HashMap<>();
        report.getAccounts().forEachKeyValue(balances::put);
        return new EmporiaPortfolioSnapshot(
                deliveryId,
                clientId,
                balances,
                change);
    }

    private static void requireCommandSuccess(
            final CommandResultCode result,
            final String operation) {
        if (result != CommandResultCode.SUCCESS) {
            throw new IllegalStateException(
                    operation + " failed: " + result);
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
            final Path storageDirectory,
            final boolean journalingEnabled) {
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
                .enableJournaling(journalingEnabled)
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
