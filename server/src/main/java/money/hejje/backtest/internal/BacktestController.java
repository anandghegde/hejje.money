package money.hejje.backtest.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.backtest.Backtest;
import money.hejje.backtest.BacktestException;
import money.hejje.backtest.BacktestService;
import money.hejje.backtest.BacktestSpec;
import money.hejje.backtest.BacktestTrade;
import money.hejje.backtest.RegimeBreakdown;
import money.hejje.backtest.FillModel;
import money.hejje.backtest.Split;
import money.hejje.backtest.Splits;
import money.hejje.common.Money;
import money.hejje.common.Timeframe;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.instruments.InstrumentService;
import money.hejje.strategy.StrategyService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/backtests")
class BacktestController {

    private final BacktestService backtests;
    private final StrategyService strategies;
    private final InstrumentService instruments;
    private final money.hejje.market.MarketService market;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    BacktestController(BacktestService backtests, StrategyService strategies, InstrumentService instruments, money.hejje.market.MarketService market,
            com.fasterxml.jackson.databind.ObjectMapper json) {
        this.json = json;
        this.backtests = backtests;
        this.strategies = strategies;
        this.instruments = instruments;
        this.market = market;
    }

    /** A Hejje symbol, or a continuous series symbol such as {@code NFO:NIFTY:FUT:CONT}. */
    private UUID instrumentIdOf(String symbol) {
        if (money.hejje.market.ContinuousSeries.isContinuousSymbol(symbol)) {
            return market.continuousSeriesBySymbol(symbol).orElseThrow(() -> new IllegalArgumentException("No continuous series " + symbol)).id();
        }
        return instruments.resolve(symbol).orElseThrow(() -> new IllegalArgumentException("Unknown instrument " + symbol)).id();
    }

    /** Either {@code versionId} or {@code strategyId + version}; instruments as Hejje symbols. Money in rupees. */
    record SubmitRequest(UUID versionId, UUID strategyId, Integer version, List<String> instruments, Timeframe timeframe, LocalDate from, LocalDate to,
            FillModel fillModel, Integer slippageBps, String costModelVersion, Splits splits, Long initialCapitalRupees, Long riskPerTradeRupees) {}

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Backtest submit(@RequestBody SubmitRequest r, @AuthenticationPrincipal HejjePrincipal principal) {
        UUID versionId = r.versionId();
        if (versionId == null) {
            if (r.strategyId() == null || r.version() == null) {
                throw new IllegalArgumentException("versionId or strategyId + version is required");
            }
            versionId = strategies.version(r.strategyId(), r.version())
                    .orElseThrow(() -> new BacktestException("Version " + r.version() + " of strategy " + r.strategyId() + " not found")).id();
        }
        List<UUID> instrumentIds = r.instruments() == null ? List.of() : r.instruments().stream().map(this::instrumentIdOf).toList();
        BacktestSpec spec = new BacktestSpec(versionId, instrumentIds, r.timeframe(), r.from(), r.to(), r.fillModel(),
                r.slippageBps() == null ? 5 : r.slippageBps(), r.costModelVersion(), r.splits(),
                r.initialCapitalRupees() == null ? null : Money.ofRupees(r.initialCapitalRupees()),
                r.riskPerTradeRupees() == null ? null : Money.ofRupees(r.riskPerTradeRupees()));
        return backtests.submit(spec, principal.name());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    List<Backtest> list(@RequestParam(required = false) UUID versionId) {
        return backtests.list(versionId);
    }

    /** The backtest plus its regime-conditional statistics ({@code byRegime}, {@code similarRegime}; plan M3.1). */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    Map<String, Object> get(@PathVariable UUID id) {
        Backtest backtest = backtests.get(id).orElseThrow(() -> new BacktestException("Backtest " + id + " not found"));
        Map<String, Object> view = json.convertValue(backtest, new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, Object>>() {});
        if (backtest.status() == money.hejje.backtest.BacktestStatus.DONE) {
            RegimeBreakdown regimes = backtests.regimeBreakdown(id, null);
            view.put("byRegime", regimes.byRegime());
            view.put("similarRegime", regimes.similar());
            view.put("similarRegimeNote", regimes.note());
        }
        return view;
    }

    @GetMapping("/{id}/regimes")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    RegimeBreakdown regimes(@PathVariable UUID id, @RequestParam(required = false) List<String> dims) {
        return backtests.regimeBreakdown(id, dims);
    }

    @GetMapping("/{id}/trades")
    @PreAuthorize("hasAuthority('SCOPE_strategies:read')")
    List<BacktestTrade> trades(@PathVariable UUID id, @RequestParam(required = false) Split split) {
        return backtests.trades(id, split);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_strategies:write')")
    Backtest cancel(@PathVariable UUID id) {
        return backtests.cancel(id);
    }
}
