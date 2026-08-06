# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

This file starts tracking from the journalling/recovery work below rather than
reconstructing the fork's full prior history; earlier changes remain available
via `git log`.

## [Unreleased]

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
