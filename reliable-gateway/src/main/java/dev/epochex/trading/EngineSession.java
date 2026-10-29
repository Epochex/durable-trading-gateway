package dev.epochex.trading;

import exchange.core2.core.*;
import exchange.core2.core.common.*;
import exchange.core2.core.common.api.*;
import exchange.core2.core.common.api.binary.BatchAddSymbolsCommand;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportQuery;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.*;
import java.util.concurrent.*;

/** The original engine owns matching and accounting; this adapter owns no matching logic. */
final class EngineSession implements AutoCloseable {
    static final int SYMBOL = 1001;
    private final ExchangeCore core;
    private final ExchangeApi api;
    private int reportId;

    EngineSession() throws Exception {
        core = ExchangeCore.builder().resultsConsumer((command, sequence) -> {})
                .exchangeConfiguration(ExchangeConfiguration.defaultBuilder()
                    .performanceCfg(PerformanceConfiguration.baseBuilder().ringBufferSize(4096).build()).build()).build();
        api = core.getApi();
        core.startup();
        try {
            require(await(api.submitBinaryDataAsync(new BatchAddSymbolsCommand(CoreSymbolSpecification.builder()
                .symbolId(SYMBOL).type(SymbolType.CURRENCY_EXCHANGE_PAIR).baseCurrency(11).quoteCurrency(15)
                .baseScaleK(1).quoteScaleK(1).takerFee(0).makerFee(0).build()))));
            for (long uid : new long[]{1, 2}) {
                require(await(api.submitCommandAsync(ApiAddUser.builder().uid(uid).build())));
                for (int currency : new int[]{11, 15}) require(await(api.submitCommandAsync(ApiAdjustUserBalance.builder()
                    .uid(uid).currency(currency).amount(1_000_000_000L).transactionId(currency).build())));
            }
        } catch (Exception | LinkageError e) { core.shutdown(); throw e; }
    }

    String apply(long sequence, CommandRequest request) throws Exception {
        ApiCommand command = "PLACE".equals(request.kind())
            ? ApiPlaceOrder.builder().uid(request.uid()).orderId(sequence).symbol(SYMBOL)
                .price(request.price()).reservePrice(request.price()).size(request.size())
                .action(OrderAction.valueOf(request.side())).orderType(OrderType.valueOf(request.orderType())).build()
            : ApiCancelOrder.builder().uid(request.uid()).orderId(request.targetOrderId()).symbol(SYMBOL).build();
        return await(api.submitCommandAsync(command)).name();
    }
    BookSnapshot book() throws Exception {
        L2MarketData book = await(api.requestOrderBookAsync(SYMBOL, 100));
        return new BookSnapshot(book.getAskPricesCopy(), book.getAskVolumesCopy(), book.getBidPricesCopy(), book.getBidVolumesCopy());
    }
    boolean balancesConserved() throws Exception {
        return await(api.processReport(new TotalCurrencyBalanceReportQuery(), ++reportId)).isGlobalBalancesAllZero();
    }
    private static void require(CommandResultCode code) {
        if (code != CommandResultCode.SUCCESS) throw new IllegalStateException("Bootstrap failed: " + code);
    }
    private static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(10, TimeUnit.SECONDS); }
    @Override public void close() { core.shutdown(); }
}
