package exchange.core2.tests.examples;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.IEventsHandler;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.*;
import exchange.core2.core.common.api.*;
import exchange.core2.core.common.api.binary.BatchAddSymbolsCommand;
import exchange.core2.core.common.api.reports.SingleUserReportQuery;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportQuery;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.ExchangeConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

@Slf4j
public class ITCoreExample {

    @Test
    public void sampleTest() throws Exception {

        // simple async events handler
        SimpleEventsProcessor eventsProcessor = new SimpleEventsProcessor(new IEventsHandler() {
            @Override
            public void tradeEvent(TradeEvent tradeEvent) {
                System.out.println("成交回调: " + tradeEvent);
            }

            @Override
            public void reduceEvent(ReduceEvent reduceEvent) {
                System.out.println("减少回调: " + reduceEvent);
            }

            @Override
            public void rejectEvent(RejectEvent rejectEvent) {
                System.out.println("拒绝回调: " + rejectEvent);
            }

            @Override
            public void commandResult(ApiCommandResult commandResult) {
                System.out.println("执行结果: " + commandResult.getCommand() + " 结果：" + commandResult.resultCode);
            }

            @Override
            public void orderBook(OrderBook orderBook) {
                System.out.println("深度回调: " + orderBook);
            }
        });

        // default exchange configuration
        ExchangeConfiguration conf = ExchangeConfiguration.defaultBuilder().build();

        // build exchange core
        ExchangeCore exchangeCore = ExchangeCore.builder()
                .resultsConsumer(eventsProcessor)
                .exchangeConfiguration(conf)
                .build();

        // start up disruptor threads
        exchangeCore.startup();

        // get exchange API for publishing commands
        ExchangeApi api = exchangeCore.getApi();

        // currency code constants
        final int currencyCodeXbt = 11;
        final int currencyCodeLtc = 15;

        // symbol constants
        final int symbolXbtLtc = 241;

        Future<CommandResultCode> future;

        // create symbol specification and publish it
        CoreSymbolSpecification symbolSpecXbtLtc = CoreSymbolSpecification.builder()
                .symbolId(symbolXbtLtc)         // symbol id
                .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(currencyCodeXbt)    // base = satoshi (1E-8) 基础币
                .quoteCurrency(currencyCodeLtc)   // quote = litoshi (1E-8) 交易币
                .baseScaleK(1_000_000L) // 1 lot = 1M satoshi (0.01 BTC) 一手合约价值0.01个比特币
                .quoteScaleK(10_000L)   // 1 price step = 10K litoshi 价格梯度 10000 ltc
                .takerFee(1900L)        // taker fee 1900 litoshi per 1 lot 吃单手续费
                .makerFee(700L)         // maker fee 700 litoshi per 1 lot 挂单手续费
                .build();

        future = api.submitBinaryDataAsync(new BatchAddSymbolsCommand(symbolSpecXbtLtc));
        System.out.println("初始化币对: " + future.get());


        // create user uid=301
        future = api.submitCommandAsync(ApiAddUser.builder()
                .uid(301L)
                .build());

        System.out.println("添加用户301结果: " + future.get());


        // create user uid=302
        future = api.submitCommandAsync(ApiAddUser.builder()
                .uid(302L)
                .build());

        System.out.println("添加用户302结果: " + future.get());

        // first user deposits 20 LTC
        future = api.submitCommandAsync(ApiAdjustUserBalance.builder()
                .uid(301L)
                .currency(currencyCodeLtc)
                .amount(2_000_000_000L)
                .transactionId(1L)
                .build());

        System.out.println("给301用户充值20LTC结果: " + future.get());


        // second user deposits 0.10 xbt
        future = api.submitCommandAsync(ApiAdjustUserBalance.builder()
                .uid(302L)
                .currency(currencyCodeXbt)
                .amount(10_000_000L)
                .transactionId(2L)
                .build());

        System.out.println("给302用户充值0.1个XBT结果: " + future.get());


        // first user places Good-till-Cancel Bid order
        // he assumes BTCLTC exchange rate 154 LTC for 1 BTC
        // bid price for 1 lot (0.01BTC) is 1.54 LTC => 1_5400_0000 litoshi => 10K * 15_400 (in price steps)
        future = api.submitCommandAsync(ApiPlaceOrder.builder()
                .uid(301L)
                .orderId(5001L)
                .price(15_400L)
                .reservePrice(15_600L) // can move bid order up to the 1.56 LTC, without replacing it
                .size(12L) // order size is 12 lots 买12手  1手是0.01 则可以买入 0.12个btc
                .action(OrderAction.BID) //买
                .orderType(OrderType.GTC) // Good-till-Cancel
                .symbol(symbolXbtLtc)
                .build());

        System.out.println("用户301下单 订单id:5001，价格15400L 花12个币 结果: " + future.get());


        // second user places Immediate-or-Cancel Ask (Sell) order
        // he assumes wost rate to sell 152.5 LTC for 1 BTC
        future = api.submitCommandAsync(ApiPlaceOrder.builder()
                .uid(302L)
                .orderId(5002L)
                .price(15_250L) //卖单价格1.525 < 1.54 所以可以成交
                .size(10L) // order size is 10 lots 卖 10手 买单是12手 所以可以全吃
                .action(OrderAction.ASK)
                .orderType(OrderType.IOC) // Immediate-or-Cancel
                .symbol(symbolXbtLtc)
                .build());

        System.out.println("用户302下单5002 卖价15250 卖10个 结果: " + future.get());


        // request order book
        CompletableFuture<L2MarketData> orderBookFuture = api.requestOrderBookAsync(symbolXbtLtc, 10);
        System.out.println("交易深度: " + orderBookFuture.get());


        // first user moves remaining order to price 1.53 LTC
        future = api.submitCommandAsync(ApiMoveOrder.builder()
                .uid(301L)
                .orderId(5001L)
                .newPrice(15_300L)
                .symbol(symbolXbtLtc)
                .build());

        System.out.println("转换价格接口 结果: " + future.get());

        // first user cancel remaining order
        future = api.submitCommandAsync(ApiCancelOrder.builder()
                .uid(301L)
                .orderId(5001L)
                .symbol(symbolXbtLtc)
                .build());

        System.out.println("取消订单接口 结果: " + future.get());

        // check balances
        Future<SingleUserReportResult> report1 = api.processReport(new SingleUserReportQuery(301), 0);
        System.out.println("单个用户301查询余额: " + report1.get().getAccounts());

        Future<SingleUserReportResult> report2 = api.processReport(new SingleUserReportQuery(302), 0);
        System.out.println("单个用户302查询余额: " + report2.get().getAccounts());

        // first user withdraws 0.10 BTC
        future = api.submitCommandAsync(ApiAdjustUserBalance.builder()
                .uid(301L)
                .currency(currencyCodeXbt)
                .amount(-10_000_000L)
                .transactionId(3L)
                .build());

        System.out.println("301用户提现0.1比特币 结果: " + future.get());

        // check fees collected
        Future<TotalCurrencyBalanceReportResult> totalsReport = api.processReport(new TotalCurrencyBalanceReportQuery(), 0);
        System.out.println("检查LTC手续费: " + totalsReport.get().getFees().get(currencyCodeLtc));

    }
}
