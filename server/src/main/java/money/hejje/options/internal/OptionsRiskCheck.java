package money.hejje.options.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import money.hejje.common.InstrumentType;
import money.hejje.common.Money;
import money.hejje.common.Side;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.MarketService;
import money.hejje.options.OptionsProperties;
import money.hejje.orders.OrderIntent;
import money.hejje.orders.OrderService;
import money.hejje.orders.Position;
import money.hejje.risk.RiskCheck;
import money.hejje.risk.RiskCheckContributor;
import money.hejje.strategy.StrategyService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Options risk controls (plan M5.4), for option intents that add exposure: lots per order, premium at risk on buys,
 * defined risk on sells (a short option needs a long option of the same underlying, expiry and type — placed first in a
 * basket — unless the strategy's deployment sets {@code allow_undefined_risk: true}), and no new option positions on
 * their expiry day from {@code hejje.options.expiry-day-cutoff}.
 */
@Component
class OptionsRiskCheck implements RiskCheckContributor {

    private final InstrumentService instruments;
    private final OrderService orders;
    private final MarketService market;
    private final ObjectProvider<StrategyService> strategies;
    private final OptionsProperties properties;
    private final HejjeClock clock;

    OptionsRiskCheck(InstrumentService instruments, OrderService orders, MarketService market, ObjectProvider<StrategyService> strategies,
            OptionsProperties properties, HejjeClock clock) {
        this.instruments = instruments;
        this.orders = orders;
        this.market = market;
        this.strategies = strategies;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public List<RiskCheck> contribute(OrderIntent intent) {
        Instrument option = instruments.findById(intent.instrumentId()).orElse(null);
        if (option == null || option.type() != InstrumentType.OPT) {
            return List.of();
        }
        List<RiskCheck> checks = new ArrayList<>();
        int lots = intent.quantity().value() / Math.max(1, option.lotSize());
        checks.add(lots > properties.maxLots()
                ? RiskCheck.fail("optionsLots", String.valueOf(lots), String.valueOf(properties.maxLots()), "too many lots in one option order")
                : RiskCheck.pass("optionsLots", lots + " lot(s)"));
        if (intent.side() == Side.BUY) {
            BigDecimal premium = intent.limitPrice() != null ? intent.limitPrice().value() : market.lastPrice(option.id()).orElse(null);
            if (premium == null) {
                checks.add(RiskCheck.pass("optionsPremium", "no price to measure the premium"));
            } else {
                Money atRisk = Money.of(premium.multiply(BigDecimal.valueOf(intent.quantity().value())).setScale(2, java.math.RoundingMode.HALF_UP));
                Money max = Money.ofRupees(properties.maxPremiumRupees());
                checks.add(atRisk.compareTo(max) > 0
                        ? RiskCheck.fail("optionsPremium", atRisk.toRupeesString(), max.toRupeesString(), "premium at risk exceeds the limit")
                        : RiskCheck.pass("optionsPremium", atRisk.toRupeesString()));
            }
        } else {
            checks.add(definedRisk(intent, option));
        }
        if (Objects.equals(option.expiry(), clock.today()) && !clock.nowIst().toLocalTime().isBefore(properties.expiryDayCutoff())) {
            checks.add(RiskCheck.fail("optionsExpiryDay", clock.nowIst().toLocalTime().toString(), properties.expiryDayCutoff().toString(),
                    "no new option positions on their expiry day from the cutoff"));
        }
        return checks;
    }

    private RiskCheck definedRisk(OrderIntent intent, Instrument option) {
        int longs = 0;
        int shorts = 0;
        for (Position p : orders.openPositions(intent.mode())) {
            if (p.netQuantity() == 0) {
                continue;
            }
            Instrument other = instruments.findById(p.instrumentId()).orElse(null);
            if (other != null && other.type() == InstrumentType.OPT && Objects.equals(other.underlying(), option.underlying())
                    && Objects.equals(other.expiry(), option.expiry()) && other.optionType() == option.optionType()) {
                if (p.netQuantity() > 0) {
                    longs += p.netQuantity();
                } else {
                    shorts -= p.netQuantity();
                }
            }
        }
        int cover = longs - shorts;
        int qty = intent.quantity().value();
        if (cover >= qty) {
            return RiskCheck.pass("optionsDefinedRisk", "covered by " + longs + " long " + option.optionType() + " (" + shorts + " already short)");
        }
        if (intent.strategyId() != null && allowsUndefinedRisk(intent)) {
            return RiskCheck.pass("optionsDefinedRisk", "undefined risk allowed by the strategy's deployment");
        }
        return RiskCheck.fail("optionsDefinedRisk", "naked short " + option.hejjeSymbol().format() + " (" + Math.max(0, cover) + " of " + qty + " covered)",
                "defined risk", "a short option needs a long option of the same underlying, expiry and type placed first, or allow_undefined_risk on the deployment");
    }

    private boolean allowsUndefinedRisk(OrderIntent intent) {
        StrategyService service = strategies.getIfAvailable();
        return service != null && service.deployments(null, intent.mode(), true).stream()
                .anyMatch(d -> intent.strategyId().equals(d.strategyId()) && Boolean.TRUE.equals(d.params().get("allow_undefined_risk")));
    }
}
