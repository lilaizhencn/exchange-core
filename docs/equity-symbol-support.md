# Equity symbol support

## Overview

`SymbolType.EQUITY` represents a listed equity as a distinct instrument type.
It uses the engine's fully funded cash-market accounting model while remaining
distinguishable from `CURRENCY_EXCHANGE_PAIR` for downstream business rules,
reporting, and adapters.

An equity symbol:

- has its own order book;
- matches orders using the existing price-time-priority behavior;
- requires a seller to hold the base equity asset;
- requires a buyer to hold enough quote currency;
- applies maker and taker fees in quote-currency units;
- participates in total asset and currency balance checks; and
- works when margin trading is disabled.

The implementation does not treat an equity as a currency pair. The two types
share cash-market accounting through `SymbolType.isCashMarket()`.

## Symbol type compatibility

The serialized symbol type codes are:

| Symbol type | Code | Accounting model |
| --- | ---: | --- |
| `CURRENCY_EXCHANGE_PAIR` | 0 | Fully funded cash market |
| `FUTURES_CONTRACT` | 1 | Margin |
| `OPTION` | 2 | Reserved; not fully supported |
| `EQUITY` | 3 | Fully funded cash market |

Existing codes remain unchanged. Snapshots and journals containing only the
previous symbol types remain compatible. A snapshot or journal containing an
equity symbol requires a binary that recognizes code `3`; an older binary will
reject that symbol type.

## Accounting and scaling

`CoreSymbolSpecification` retains the legacy `baseCurrency` field name. For an
equity, this field contains the internal account identifier for the equity
asset, not an ISO currency code.

| Field | Equity meaning |
| --- | --- |
| `symbolId` | Internal identifier for the listed equity order book |
| `baseCurrency` | Internal account identifier for the equity asset |
| `quoteCurrency` | Internal identifier for the trading currency |
| `baseScaleK` | Base-asset units represented by one order lot |
| `quoteScaleK` | Smallest quote-currency units represented by one price step |
| `makerFee` | Maker fee per lot in quote-currency units |
| `takerFee` | Taker fee per lot in quote-currency units |

All values use integer arithmetic:

```text
base asset held for an ASK = size × baseScaleK
quote currency held for a BID = size × (reservePrice × quoteScaleK + takerFee)
```

For example, when accounts store whole shares and USD cents:

- `baseScaleK = 1` means one order lot is one share;
- `quoteScaleK = 1` means one price step is one cent; and
- an order price of `18_532` represents USD 185.32.

Fractional shares can be represented by choosing a smaller base-asset unit and
an appropriate `baseScaleK`. The adapter that creates symbol specifications
must keep the asset, price, quantity, and fee scales consistent.

## Configuration example

The following specification creates an AAPL/USD cash-equity order book:

```java
final int symbolAaplUsd = 10_001;
final int assetAapl = 20_001;
final int currencyUsd = 840;

CoreSymbolSpecification aapl = CoreSymbolSpecification.builder()
        .symbolId(symbolAaplUsd)
        .type(SymbolType.EQUITY)
        .baseCurrency(assetAapl)
        .quoteCurrency(currencyUsd)
        .baseScaleK(1)       // one lot is one whole share
        .quoteScaleK(1)      // prices and balances use USD cents
        .makerFee(0)
        .takerFee(0)
        .build();

api.submitBinaryDataAsync(new BatchAddSymbolsCommand(aapl)).join();
```

An account selling five shares must first have at least five units in the
`assetAapl` account. A buyer bidding USD 185.32 for five shares, with a maximum
reserved price of USD 185.50, submits:

```java
ApiPlaceOrder buy = ApiPlaceOrder.builder()
        .uid(101L)
        .orderId(5_001L)
        .symbol(symbolAaplUsd)
        .action(OrderAction.BID)
        .orderType(OrderType.GTC)
        .price(18_532L)
        .reservePrice(18_550L)
        .size(5L)
        .build();

api.submitCommandAsync(buy).join();
```

The buyer must have at least `5 × 18_550 = 92_750` cents available, plus any
configured taker fee. The difference between the reserved price and the actual
execution price is released after execution. Moving the bid above its reserved
price is rejected.

## Order lifecycle

1. On an accepted ASK, the required equity units are removed from the seller's
   available base-asset balance.
2. On an accepted BID, the required cash and taker fee are removed from the
   buyer's available quote-currency balance.
3. A trade credits equity units to the buyer and execution proceeds, less fees,
   to the seller.
4. Price improvement releases unused reserved cash to the buyer.
5. Cancellation, reduction, or unmatched IOC quantity releases the remaining
   held asset or cash.
6. Total balance reporting includes assets and cash held in open equity orders.

This is deterministic in-engine asset and cash accounting. It is not external
securities settlement.

The optional production-simulation adapter can seed these balances from a
future Emporia portfolio service and publish post-command available-balance
snapshots. See [Production simulation](production-simulation.md#optional-emporia-portfolio-accounting).

## Current limitations

Equity support does not currently provide:

- exchange sessions, opening or closing auctions, or volatility halts;
- corporate actions such as splits, dividends, symbol changes, or delistings;
- locate, borrow, or short-selling workflows;
- regulatory price bands or stock-specific order validation;
- external clearing, custody, or T+1 settlement;
- market-data or FIX/REST gateways; or
- a mapping from external listing, account, and order identifiers to the
  engine's integer and `long` identifiers.

These concerns belong in the integration and post-trade layers. The matching
engine currently provides the central limit order book and fully funded
pre-trade/post-trade accounting behavior.

## Tests

Equity behavior is covered by:

- `SymbolTypeTest` for codes and cash-market classification;
- `OrderBookDirectImplEquityTest` and `OrderBookNaiveImplEquityTest` for both
  order-book implementations; and
- `EquitySymbolTest` for insufficient-funds rejection, trading with margin
  disabled, share/cash transfers, and the global balance invariant.

Run the focused tests with:

```shell
mvn -q \
  -Dtest=SymbolTypeTest,OrderBookDirectImplEquityTest,OrderBookNaiveImplEquityTest,EquitySymbolTest \
  test
```
