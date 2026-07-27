# exchange-core architecture

## Purpose

exchange-core is a high-performance Java exchange matching engine and risk/accounting core. It is designed for low-latency order handling, deterministic matching, and durable state recovery.

## Main responsibilities

- Accept trading commands such as place, cancel, reduce, move, and replace orders
- Validate commands against risk and account state
- Match incoming orders against resting orders in one or more order books
- Emit results and trade/reject events to downstream consumers
- Support journaling and snapshot-based recovery

## Runtime architecture

The runtime is built around a Disruptor pipeline managed by ExchangeCore.

1. Commands enter through ExchangeApi
   - This is the public submission entry point.
   - It accepts trading and administrative commands.

2. The Disruptor pipeline processes each command
   - Grouping processors prepare the command for downstream stages.
   - Journaling can persist state if enabled.
   - Risk engines validate user/account and trading constraints.
   - Matching engines route orders to the correct order-book shard.
   - Results are emitted after matching and risk release.

3. Matching and risk are sharded
   - MatchingEngineRouter owns the order books and routing logic.
   - RiskEngine owns user profiles, balances, fees, and risk checks.

## Order lifecycle

A typical order flows as follows:

1. A client submits a command through ExchangeApi.
2. The command is published into the Disruptor ring buffer.
3. Risk pre-processing validates the request.
4. The matching engine tries to fill the order against resting liquidity.
5. If the order is fully filled, it completes.
6. If it is partially filled, the remaining quantity may rest in the order book.
7. If it is invalid or unfillable, a reject event may be emitted.
8. The engine returns a command result and any trade/reject events to the caller.

## Matching engine internals

The matching logic is implemented in the order-book package.

- OrderBookDirectImpl is the high-performance implementation.
- It supports GTC, IOC, and FOK-style behaviors.
- It maintains price buckets, order indices, and best bid/ask references.
- It creates trade, reduce, and reject events during processing.

## Event model

The engine produces event chains attached to each command.

- TRADE events describe fills between resting and incoming orders.
- REDUCE events describe cancel/reduce operations.
- REJECT events describe invalid or unfilled orders.
- SimpleEventsProcessor turns these into higher-level callbacks for consumers.

## Design characteristics

- Low-latency and high-throughput by design
- Deterministic matching behavior
- Sharded matching and risk processing
- Durable journal/snapshot support for recovery
- Minimal garbage pressure and optimized object handling

## Important classes

- ExchangeCore: main orchestrator and Disruptor wiring
- ExchangeApi: public command submission API
- MatchingEngineRouter: order-book routing and matching entry point
- RiskEngine: risk, balances, and account validation
- OrderBookDirectImpl: core matching engine implementation
- OrderBookEventsHelper: creates matcher events
- SimpleEventsProcessor: emits results and events to handlers
