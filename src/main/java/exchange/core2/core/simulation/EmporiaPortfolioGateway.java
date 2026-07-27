package exchange.core2.core.simulation;

import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous port for an Emporia portfolio service.
 *
 * <p>The service supplies one-time simulation seeds and accepts idempotent
 * post-command balance snapshots. Implementations must deduplicate published
 * snapshots by {@code (deliveryId, clientId)}.</p>
 */
public interface EmporiaPortfolioGateway {

    CompletableFuture<EmporiaPortfolioSeed> load(long clientId);

    CompletableFuture<Void> publish(EmporiaPortfolioSnapshot snapshot);
}
