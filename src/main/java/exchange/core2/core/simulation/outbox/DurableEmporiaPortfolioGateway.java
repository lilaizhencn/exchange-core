package exchange.core2.core.simulation.outbox;

import exchange.core2.core.simulation.EmporiaPortfolioGateway;
import exchange.core2.core.simulation.EmporiaPortfolioSeed;
import exchange.core2.core.simulation.EmporiaPortfolioSnapshot;
import exchange.core2.core.simulation.http.EmporiaPortfolioHttpEvent;
import exchange.core2.core.simulation.http.HttpEmporiaPortfolioGateway;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gateway that durably accepts snapshots before an asynchronous HTTP worker
 * delivers them to Emporia.
 */
public final class DurableEmporiaPortfolioGateway
        implements EmporiaPortfolioGateway, AutoCloseable {

    private final HttpEmporiaPortfolioGateway httpGateway;
    private final PortfolioOutboxStore store;
    private final PortfolioOutboxPublisher publisher;
    private final ExecutorService databaseExecutor;

    private DurableEmporiaPortfolioGateway(
            final HttpEmporiaPortfolioGateway httpGateway,
            final PortfolioOutboxStore store,
            final PortfolioOutboxPublisher publisher,
            final ExecutorService databaseExecutor) {
        this.httpGateway =
                Objects.requireNonNull(httpGateway, "httpGateway");
        this.store = Objects.requireNonNull(store, "store");
        this.publisher =
                Objects.requireNonNull(publisher, "publisher");
        this.databaseExecutor =
                Objects.requireNonNull(
                        databaseExecutor,
                        "databaseExecutor");
    }

    public static DurableEmporiaPortfolioGateway start(
            final HttpEmporiaPortfolioGateway httpGateway,
            final DataSource dataSource,
            final PortfolioOutboxConfiguration configuration) {
        final PostgresPortfolioOutbox store =
                new PostgresPortfolioOutbox(dataSource);
        final PortfolioOutboxPublisher publisher =
                new PortfolioOutboxPublisher(
                        store,
                        httpGateway,
                        configuration);
        final ExecutorService databaseExecutor =
                Executors.newFixedThreadPool(
                        2,
                        task -> {
                            final Thread thread = new Thread(
                                    task,
                                    "emporia-portfolio-outbox-enqueue");
                            thread.setDaemon(true);
                            return thread;
                        });
        final DurableEmporiaPortfolioGateway gateway =
                new DurableEmporiaPortfolioGateway(
                        httpGateway,
                        store,
                        publisher,
                        databaseExecutor);
        publisher.start();
        return gateway;
    }

    @Override
    public CompletableFuture<EmporiaPortfolioSeed> load(
            final long clientId) {
        return httpGateway.load(clientId);
    }

    /**
     * Completes after the immutable event is committed to PostgreSQL.
     */
    @Override
    public CompletableFuture<Void> publish(
            final EmporiaPortfolioSnapshot snapshot) {
        final EmporiaPortfolioHttpEvent event =
                httpGateway.encode(snapshot);
        return CompletableFuture.runAsync(
                () -> store.enqueue(event),
                databaseExecutor);
    }

    public CompletableFuture<Integer> drainOnce() {
        return publisher.drainOnce();
    }

    @Override
    public void close() {
        publisher.close();
        databaseExecutor.shutdownNow();
    }
}
