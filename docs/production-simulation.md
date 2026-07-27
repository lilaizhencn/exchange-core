# Production simulation

`ProductionSimulation` is a durable `MATCHING_ONLY` harness for exercising the
DMA lifecycle against exchange-core without risk accounting or deployment
infrastructure.

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
cancelled and rejected quantity. It also reports approximate p50, p95, p99 and
maximum latency plus aggregate successful operations per second.

Cached duplicate responses do not count their fills or quantities twice.

## Benchmarks

The standalone [`benchmarks/`](../benchmarks/README.md) JMH project measures
partition dispatch, protected IOC round trips and durable checkpoint latency.
