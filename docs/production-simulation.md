# Production simulation

`ProductionSimulation` is a durable DMA harness with two accounting modes:

- `MATCHING_ONLY` for externally risk-managed order flow; and
- `FULL_EQUITY_RISK` for fully funded share and cash accounting with an
  asynchronous Emporia portfolio-service boundary.

## Symbol-partition ordering

Every DMA operation is published through one serial lane selected by:

```text
partition = symbol & (symbolPartitions - 1)
```

The partition count must be a power of two and equal the matching-engine count.
Operations for one symbol are published FIFO while different partitions can
publish concurrently. Results expose the partition and publication sequence.

## Checkpoints and recovery

A checkpoint first persists every native exchange shard. It then writes the
DMA lifecycle to a versioned, CRC32C-protected temporary file, forces the
contents, atomically renames it and forces the parent directory.

The lifecycle file is the checkpoint commit marker. Recovery loads it before
starting exchange-core from the matching checkpoint ID. Missing, truncated or
corrupt lifecycle data rejects recovery. Partial fills and completed delivery
responses are restored, so idempotent duplicate delivery survives restart.

This is checkpoint recovery, not continuous journal replay. Operations after
the latest committed checkpoint are outside its recovery boundary.

## Metrics

`ProductionSimulation.metrics()` provides cumulative lock-free counters for
submissions, completions, failures, duplicate deliveries, fills, and filled,
cancelled and rejected quantity. Portfolio publication failures are reported
separately while retaining the exchange fill or rejection outcome. Metrics
also include approximate p50, p95, p99 and maximum latency plus aggregate
successful operations per second.

Cached duplicate responses do not count their fills or quantities twice.

## Optional Emporia portfolio accounting

`ProductionSimulationAccounting.fullEquityRisk(gateway)` configures
`FULL_PER_CURRENCY` risk with margin disabled. In this mode:

1. `onboardPortfolio(clientId)` imports a one-time
   `EmporiaPortfolioSeed`;
2. the core rejects an ASK without sufficient equity and a BID without
   sufficient cash;
3. successful trades transfer equity and cash in-engine;
4. every completed DMA delivery publishes the affected clients'
   `EmporiaPortfolioSnapshot` objects; and
5. `adjustPortfolioBalance(...)` applies an idempotent funding transaction and
   republishes the resulting available balances.

The gateway remains transport-neutral. A concrete asynchronous HTTP
implementation is provided by `HttpEmporiaPortfolioGateway`. Published
snapshots must be deduplicated by `(deliveryId, clientId)`. A failed
publication does not roll back an exchange command; redelivering the same DMA
request republishes the cached result and portfolio snapshots.

Portfolio snapshots contain available balances. Funds reserved by live orders
remain represented by the order projection and are returned to available
balances on cancellation.

Full-equity-risk mode accepts only `EQUITY` symbols. DMA limit, protected IOC,
and cancel operations are supported. Atomic replace remains matching-only
because the full risk engine does not yet maintain enough reservation metadata
to re-reserve an existing order atomically.

Native risk state and DMA lifecycle state use the same checkpoint. The
accounting mode is stored in the lifecycle commit marker, so recovery rejects
a mode mismatch.

### Emporia HTTP adapter

The adapter calls these endpoints relative to its configured base URI:

```text
GET internal/v1/portfolios/{clientId}/risk-seed
PUT internal/v1/portfolio-snapshots/{deliveryId}/{clientId}
```

The seed response uses a balance array so numeric asset identifiers do not
become JSON object keys:

```json
{
  "schemaVersion": 1,
  "clientId": 101,
  "firstTransactionId": 7001,
  "balances": [
    {"assetId": 840, "amount": 50000},
    {"assetId": 20001, "amount": 12}
  ]
}
```

Snapshot requests are deterministic and sort balances by asset ID:

```json
{
  "schemaVersion": 1,
  "exchangeId": "emporia-simulation-1",
  "deliveryId": 13,
  "clientId": 101,
  "availableBalances": [
    {"assetId": 840, "amount": 0},
    {"assetId": 20001, "amount": 5}
  ]
}
```

Every snapshot request includes:

```text
Content-Type: application/json
Accept: application/json
Idempotency-Key: {exchangeId}:{deliveryId}:{clientId}
```

Emporia must return any `2xx` response for a new snapshot or an identical
duplicate. It must reject reuse of the idempotency key with a different body.
The adapter marks HTTP `408`, `429`, and `5xx` responses, plus transport
failures, as retryable through `EmporiaHttpException.retryable()`. It does not
retry internally.

Configure and wire the adapter with credentials supplied by the runtime:

```java
var gatewayConfiguration = new EmporiaHttpGatewayConfiguration(
        URI.create(System.getenv("EMPORIA_PORTFOLIO_BASE_URL")),
        "emporia-simulation-1",
        Duration.ofSeconds(3),
        Map.of(
                "Authorization",
                "Bearer " + System.getenv("EMPORIA_PORTFOLIO_TOKEN")));

var gateway = new HttpEmporiaPortfolioGateway(gatewayConfiguration);
var accounting =
        ProductionSimulationAccounting.fullEquityRisk(gateway);
var simulation =
        ProductionSimulation.start(configuration, accounting);
```

The direct HTTP gateway remains useful for development: its
`CompletableFuture` completes when Emporia returns a successful response. Use
`DurableEmporiaPortfolioGateway` in a production simulation. Its future
completes after PostgreSQL commits the immutable outbound event, while a
separate lease-based worker delivers that event to Emporia.

### Durable portfolio outbox

Apply
`src/main/resources/db/portfolio-outbox/V1__create_portfolio_outbox.sql` to a
PostgreSQL database owned by the embedding exchange process. Then supply a
managed JDBC `DataSource`:

```java
var http = new HttpEmporiaPortfolioGateway(gatewayConfiguration);
var outbox = DurableEmporiaPortfolioGateway.start(
        http,
        dataSource,
        PortfolioOutboxConfiguration.defaults(instanceId));
var accounting =
        ProductionSimulationAccounting.fullEquityRisk(outbox);

try (outbox;
     var simulation =
             ProductionSimulation.start(configuration, accounting)) {
    // Submit DMA operations.
}
```

The outbox stores the exact encoded request bytes and SHA-256 digest. Enqueuing
the same event and content is idempotent; reusing an event ID with different
content fails. Workers atomically claim ready rows with a lease, deliver
different clients concurrently, and preserve sequence order for one client.
An expired `IN_FLIGHT` lease is eligible for another worker after a process
crash.

Transport failures, HTTP `408`, `429`, and `5xx` responses move a row to
`RETRY` with capped exponential jitter. Other HTTP `4xx` responses move it to
`DEAD`. Because events are full snapshots, a dead row does not block a later
snapshot forever; operators should still alert and investigate it. A successful
or identical duplicate response moves the row to `PUBLISHED`.

Published and dead rows are retained for audit and idempotency rather than
deleted automatically. Define an operator-owned retention or archival job
before sustained load; never purge pending, retrying, or leased rows.

Useful backlog checks include:

```sql
SELECT status, count(*), min(created_at)
FROM exchange_core_portfolio_outbox
GROUP BY status;

SELECT event_id, client_id, attempt_count, next_attempt_at, last_error
FROM exchange_core_portfolio_outbox
WHERE status IN ('RETRY', 'DEAD')
ORDER BY sequence_id;
```

Run the real PostgreSQL outbox specification with:

```bash
mvn -Ppostgres-it test
```

This verifies duplicate enqueue, per-client ordering, and reclaim after a
publisher process loses its lease.

#### Crash boundary

The outbox guarantees delivery survival once `publish()` commits the row. It
does not make the earlier exchange mutation and outbox insert one database
transaction. `ProductionSimulation` currently has checkpoint recovery with
continuous journaling disabled, so a crash after an exchange command but
before enqueue remains a gap.

Closing that final gap requires continuously journaling the DMA inbox and
lifecycle and deterministically replaying every operation after the last
checkpoint, including regeneration of the same portfolio event ID and payload.
Do not claim strict end-to-end crash recovery until that replay boundary is
implemented and tested.

## Benchmarks

The standalone [`benchmarks/`](../benchmarks/README.md) JMH project measures
partition dispatch, matching-only and full-risk protected IOC round trips,
portfolio publication, and durable checkpoint latency.
