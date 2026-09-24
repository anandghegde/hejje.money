package money.hejje.risk.internal;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.Price;
import money.hejje.common.Side;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.risk.KillSwitchAction;
import money.hejje.risk.KillSwitchState;
import money.hejje.risk.RiskDashboard;
import money.hejje.risk.RiskLimits;
import money.hejje.risk.RiskService;
import money.hejje.risk.StopSuggestion;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/risk")
class RiskController {

    private final RiskService risk;
    private final HejjeProperties properties;

    RiskController(RiskService risk, HejjeProperties properties) {
        this.risk = risk;
        this.properties = properties;
    }

    private ExecutionMode mode() {
        return properties.mode();
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_risk:read')")
    RiskDashboard dashboard() {
        return risk.dashboard(mode());
    }

    @GetMapping("/limits")
    @PreAuthorize("hasAuthority('SCOPE_risk:read')")
    RiskLimits limits() {
        return risk.limits(mode());
    }

    record LimitsRequest(
            long maxLossPerDayPaise, long maxRealizedLossPaise, long maxTotalLossPaise, long maxCapitalDeployedPaise,
            BigDecimal maxMarginUtilizationPct, int maxOpenPositions, long maxGrossExposurePaise, int maxTradesPerDay,
            long maxRiskPerTradePaise, int maxQuantity, long maxNotionalPaise, BigDecimal minRewardRisk, boolean mandatoryStop,
            BigDecimal maxStopDistancePct, @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime noNewTradesAfter,
            boolean noAveragingDown, int noReentryMinutes, int maxConsecutiveLosses, RiskLimits.LossStreakMode lossStreakMode,
            Long allowanceDrawdownPaise, Integer lossStreakAllowance, RiskLimits.TradesWhenGreen tradesPerDayWhenGreen) {
    }

    @PutMapping("/limits")
    @PreAuthorize("hasAuthority('SCOPE_risk:write')")
    RiskLimits updateLimits(@RequestBody LimitsRequest r, @AuthenticationPrincipal HejjePrincipal principal) {
        RiskLimits current = risk.limits(mode());
        RiskLimits limits = new RiskLimits(mode(), Money.ofPaise(r.maxLossPerDayPaise()), Money.ofPaise(r.maxRealizedLossPaise()),
                Money.ofPaise(r.maxTotalLossPaise()), Money.ofPaise(r.maxCapitalDeployedPaise()), r.maxMarginUtilizationPct(),
                r.maxOpenPositions(), Money.ofPaise(r.maxGrossExposurePaise()), r.maxTradesPerDay(), Money.ofPaise(r.maxRiskPerTradePaise()),
                r.maxQuantity(), Money.ofPaise(r.maxNotionalPaise()), r.minRewardRisk(), r.mandatoryStop(), r.maxStopDistancePct(),
                r.noNewTradesAfter(), r.noAveragingDown(), r.noReentryMinutes(), r.maxConsecutiveLosses(), r.lossStreakMode(),
                r.allowanceDrawdownPaise() == null ? null : Money.ofPaise(r.allowanceDrawdownPaise()),
                r.lossStreakAllowance() == null ? current.lossStreakAllowance() : r.lossStreakAllowance(), r.tradesPerDayWhenGreen());
        // plan M9.7 fields left out of a request keep their current values
        limits = new RiskLimits(limits.mode(), limits.maxLossPerDay(), limits.maxRealizedLoss(), limits.maxTotalLossInclUnrealized(),
                limits.maxCapitalDeployed(), limits.maxMarginUtilizationPct(), limits.maxOpenPositions(), limits.maxGrossExposure(), limits.maxTradesPerDay(),
                limits.maxRiskPerTrade(), limits.maxQuantity(), limits.maxNotional(), limits.minRewardRisk(), limits.mandatoryStop(),
                limits.maxStopDistancePct(), limits.noNewTradesAfter(), limits.noAveragingDown(), limits.noReentryMinutes(), limits.maxConsecutiveLosses(),
                r.lossStreakMode() == null ? current.lossStreakMode() : r.lossStreakMode(),
                r.allowanceDrawdownPaise() == null ? current.allowanceDrawdown() : limits.allowanceDrawdown(), limits.lossStreakAllowance(),
                r.tradesPerDayWhenGreen() == null ? current.tradesPerDayWhenGreen() : r.tradesPerDayWhenGreen());
        return risk.updateLimits(limits, principal.name());
    }

    record KillSwitchRequest(@NotNull KillSwitchAction action, String confirmation) {}

    @GetMapping("/kill-switch")
    @PreAuthorize("hasAuthority('SCOPE_risk:read')")
    KillSwitchState killSwitch() {
        return risk.killSwitch(mode());
    }

    @PostMapping("/kill-switch")
    @PreAuthorize("hasAuthority('SCOPE_risk:write')")
    KillSwitchState activate(@RequestBody KillSwitchRequest body, @AuthenticationPrincipal HejjePrincipal principal) {
        return risk.activate(mode(), body.action(), body.confirmation(), principal.name());
    }

    @DeleteMapping("/kill-switch")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    KillSwitchState rearm(@AuthenticationPrincipal HejjePrincipal principal) {
        return risk.rearm(mode(), principal.name());
    }

    record SizeRequest(@NotNull Price entry, @NotNull Price stop, long riskPaise, int lotSize, int maxQuantity) {}

    @PostMapping("/position-size")
    @PreAuthorize("hasAuthority('SCOPE_risk:read')")
    java.util.Map<String, Object> positionSize(@RequestBody SizeRequest r) {
        int qty = risk.positionSize(r.entry(), r.stop(), Money.ofPaise(r.riskPaise()), r.lotSize() <= 0 ? 1 : r.lotSize(), r.maxQuantity());
        return java.util.Map.of("quantity", qty);
    }

    @GetMapping("/stop-suggestion")
    @PreAuthorize("hasAuthority('SCOPE_risk:read')")
    StopSuggestion stopSuggestion(@RequestParam UUID instrumentId, @RequestParam Side side, @RequestParam(required = false) Price entry) {
        return risk.suggestStop(mode(), instrumentId, side, entry);
    }
}
