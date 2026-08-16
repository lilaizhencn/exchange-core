# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

This file starts tracking from the journalling/recovery work below rather than
reconstructing the fork's full prior history; earlier changes remain available
via `git log`.

## [0.5.15-emporia]

### Security

- Compile and package from an immutable archive of the attested clean Git commit.
- Re-attest the repository and packaged provenance after the executable JAR is created, rejecting source or HEAD
  changes made during the build.

## [0.5.14-emporia]

### Added

- Open-order reports preserve their native traversal order and reject duplicate IDs in one linear pass.
- Reproducible builds reject dirty Git worktrees and embed the exact clean source commit for downstream verification.

### Verified

- Two clean JDK 25 builds produce the same whole-JAR SHA-256.
- The full fork suite covers native snapshot restore, FIFO priority, linear report order and duplicate rejection.

## [0.5.10-emporia]

### Added

- Added a single-pass open-order report for exact, O(open orders) reconciliation
  after native snapshot restore.
- Added atomic snapshot import, CRC32C metadata, deterministic concurrent export,
  and explicit in-memory snapshot removal.

## [0.5.9-emporia]

### Added

- Added a snapshot-only in-memory serialization processor. Callers can export
  exchange-core's native matching/risk module blobs into an external consensus
  snapshot and import them before starting with `fromSnapshotOnly`, without a
  second order book or an O(open orders) replay rebuild.

### Verified

- `InMemorySerializationProcessorTest`: native snapshot export/import restores
  the same state hash, balances, order-book volumes, and FIFO priority.

## [0.5.8-emporia]

### Fixed

- **The portfolio outbox no longer grows faster than it can drain.** Every
  accepted command published a balance snapshot, settled or not, so the queue
  filled at order-flow rate while it could only empty at delivery rate. Measured
  at 120 orders/sec for ten minutes: **72,002 rows enqueued, 248 delivered**, and
  the claim query's per-client anti-join reached **2,271 ms** once ~72k rows were
  pending - 71,753 subquery loops per call - which held a Postgres core at 102%
  indefinitely. Snapshots are now classified: `SETTLED` (a fill, or a funding
  adjustment) is delivered and acknowledged individually and is never collapsed,
  because each one is an audit record; `RESERVED` (margin moving on an order
  that has not traded) supersedes this client's earlier undelivered reservation,
  since only the newest one carries anything. Re-measured on the same workload:
  **0 pending, 100% resolved, Postgres back to 0.88%**.
  ([EmporiaPortfolioChange](https://github.com/nvxtien/exchange-core))

- **Enqueue is single-threaded.** Superseding compares `sequence_id`, so
  insertion order has to match the order snapshots were produced in. With the
  previous two-thread pool a newer snapshot could be written first and then
  superseded by an older one, publishing a stale balance.

### Verified

- `PostgresPortfolioOutboxSpec`: reservations collapse to one pending row per
  client; settled changes never collapse; a reservation does not supersede an
  undelivered settled change; superseding is scoped to one client; a published
  reservation is not revived.
- `ProductionSimulationAccountingTest`: a resting order classifies as
  `RESERVED`, a trade classifies as `SETTLED` for taker and every maker.

## [0.5.7-emporia]

### Fixed

- **`portfolioSnapshot()` no longer scans the order book on every command.**
  It only ever reads risk-engine accounts (`report.getAccounts()`), but
  `SingleUserReportQuery` always ran both report stages, including
  `MatchingEngineRouter`'s `findUserOrders` — an unindexed linear scan over
  every order in the book — even though the result
  (`report.fetchIndexedOrders()`) was never read at this call site. Under
  `FULL_EQUITY_RISK`, every accepted command (and every fill) synchronously
  pays for this scan before completing, so cost grew with book size: p99
  reached 1,934ms with 50% infra failures on an ~8,300-order book at 60
  orders/sec, most of it in `findUserOrders`/`SingleUserReportResult`/
  `ArtNode256` per JFR. `SingleUserReportQuery` now takes an
  `includeOpenOrders` flag (default `true`, unchanged for
  `openOrderIds()`/reconciliation, which does need the order list);
  `portfolioSnapshot()` passes `false` and skips the scan entirely.
  ([cb2e35d](https://github.com/nvxtien/exchange-core/commit/cb2e35d))

### Verified

- `ProductionSimulationAccountingTest.portfolioSnapshotSkipsTheOrderScanWithoutLosingBalanceAccuracy`
  — places a resting (unfilled) order, then confirms `openOrderIds()` still
  finds it (scan path unaffected) while `portfolioSnapshot()` still reports
  the correct risk-engine-reserved balance despite never looking at the
  order itself.
- JFR, identical 60 orders/sec / 60s load, before vs. after: p99 1,934ms →
  292ms on a book *larger* than the one that produced the regression
  (~12,900 vs. ~8,300 orders); `findUserOrders`/`SingleUserReportResult`/
  `ArtNode256` absent from `hot-methods` entirely after the fix.

## [0.5.6-emporia]

Work toward making journalled (write-ahead log) recovery safe to enable,
verified by a `kill -9` test against a running order-management-service rather
than by unit tests alone.

### Fixed

- **A recovered exchange now resumes journalling.** `writeToJournal` compared
  the live disruptor's sequence (`dSeq`, which restarts near 1 on every
  process) against `enableJournalAfterSeq`, a boundary recorded by the
  *previous* process. Every command after a recovery therefore looked like one
  already being replayed and was silently dropped — a second crash lost
  everything accepted since the first recovery, with nothing indicating it.
  The boundary is now the count of commands actually replayed through the API,
  in the current process's own sequence space.
  ([9ca7099](https://github.com/nvxtien/exchange-core/commit/9ca7099))

- **`shutdown()` no longer hangs when nothing was journalled.** The
  `SHUTDOWN_SIGNAL` branch of `writeToJournal` called `flushBufferSync` before
  the journal channel's lazy `if (channel == null) startNewFile(...)` guard
  further down. A shutdown with no intervening mutating command hit a null
  channel inside the journal handler, the disruptor never drained, and
  `ExchangeCore.shutdown()` threw `IllegalStateException: could not stop a
  disruptor gracefully` after its 5-second timeout. Fixed with an explicit
  guard: if nothing has been journalled yet, there is nothing to flush.
  ([9ca7099](https://github.com/nvxtien/exchange-core/commit/9ca7099))

- **Journal file names no longer collide after recovery.** Journal files are
  named `<exchange>_journal_<snapshotId>_<counter>`, and `filesCounter`
  restarted at zero on every process start. Because the channel opens lazily,
  the first mutating command after a recovery tried to create the same file it
  had just replayed and failed with `File already exists` — the exchange came
  up and then silently accepted nothing. `filesCounter` now continues from the
  last index actually consumed by replay, so the next file written is the
  first free one. (Note: an immediate post-recovery snapshot does not work
  around this — the snapshot command is itself mutating and hits the same
  lazy-open path first.)
  ([c58854d](https://github.com/nvxtien/exchange-core/commit/c58854d))

### Added

- **`ProductionSimulation.recoverLifecycle(DmaLifecycleSnapshot)`** lets a
  caller that persists order state elsewhere (e.g. an external
  order-management service) rebuild and apply the DMA lifecycle projection
  after a journalled recovery. The journal restores the matching engine but
  not the lifecycle — the lifecycle lives on the API side
  (`DmaOrderLifecycleService`, plain `HashMap`s) while journal replay drives
  the disruptor directly, so without this a journalled recovery leaves the
  book holding orders the lifecycle layer has no record of, and every later
  operation on them fails with `unknown lifecycle order`.
  ([c58854d](https://github.com/nvxtien/exchange-core/commit/c58854d))

- **Journalling is now configurable** via
  `ProductionSimulationConfiguration.journalingEnabled` (default `false`).
  Previously `snapshotSerialization()` built a full journal configuration and
  then unconditionally switched it off, leaving the per-command snapshot as
  the only durability mechanism — which is why it sat on the command path.
  With it enabled, a clean start uses `cleanStartJournaling(...)` and recovery
  uses `lastKnownStateFromJournal(...)`, so replay picks up commands accepted
  after the last snapshot instead of discarding them the way
  `fromSnapshotOnly(...)` does.
  ([0752bb5](https://github.com/nvxtien/exchange-core/commit/0752bb5))

- The exchange-core storage/snapshot/journal folder is now created before the
  first snapshot write, rather than assuming it exists.
  ([1408fc7](https://github.com/nvxtien/exchange-core/commit/1408fc7))

### Verified

- `ProductionSimulationTest.shouldKeepJournallingAfterRecovery` — submit,
  snapshot, submit more, recover twice; the second recovery now sees every
  order submitted before the first, proving journalling survives a recovery
  rather than silently stopping.
- `ProductionSimulationTest.shouldReplayCommandsJournalledAfterTheLastSnapshot`
  — proves the matching engine correctly replays commands accepted after the
  last snapshot.
- Downstream, against a live `order-management-service` process: concurrent
  order submission, `kill -9` mid-burst, confirmed via direct Postgres query
  that unflushed orders existed at the moment of the kill, and confirmed via
  application logs that the write-ahead log replayed them cleanly on restart
  with zero replay failures. See the `emporia` wiki,
  *WAL Crash Recovery Verification*, for the full step-by-step record
  (including two earlier attempts that passed without exercising the crash
  window at all).

### Known limitations

- The lifecycle rebuilt via `recoverLifecycle` restores dedup only for the
  **current** version of each order — the source of truth (e.g. an external
  order-management database) records current state, not the full history of
  delivery IDs the engine has answered. This is not a limitation of
  `recoverLifecycle` itself so much as of what any external system can supply.
