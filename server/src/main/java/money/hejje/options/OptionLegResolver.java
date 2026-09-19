package money.hejje.options;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import money.hejje.common.InstrumentType;
import money.hejje.common.OptionType;
import money.hejje.common.Side;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.strategy.StrategyDefinition;
import org.springframework.stereotype.Component;

/**
 * Turns a strategy's {@code legs} into concrete option orders for a signal on the underlying (plan M5.4): expiry,
 * option type (directional legs follow the signal's side), strike by ATM, point offset or delta, quantity in lots, and
 * the premium stop/target of each leg. Deterministic for the same chain.
 */
@Component
public class OptionLegResolver {

    private final InstrumentService instruments;
    private final OptionChainService chains;
    private final OptionsProperties properties;
    private final HejjeClock clock;

    OptionLegResolver(InstrumentService instruments, OptionChainService chains, OptionsProperties properties, HejjeClock clock) {
        this.instruments = instruments;
        this.chains = chains;
        this.properties = properties;
        this.clock = clock;
    }

    /** @param entryReference the leg's last price when resolved; the stop/target are percent of it */
    public record ResolvedLeg(int sequence, Instrument instrument, Side side, int quantity, BigDecimal entryReference, BigDecimal stopPrice,
            BigDecimal targetPrice, boolean hedgeFirst) {}

    /** The option underlying of a signal instrument: a future's or option's underlying, an index's mapped name, else the symbol. */
    public String optionUnderlying(Instrument signalInstrument) {
        if (signalInstrument.type() == InstrumentType.FUT || signalInstrument.type() == InstrumentType.OPT) {
            return signalInstrument.underlying();
        }
        if (signalInstrument.type() == InstrumentType.INDEX) {
            return properties.underlyings().getOrDefault(signalInstrument.symbol(), signalInstrument.symbol());
        }
        return signalInstrument.symbol();
    }

    /** @param direction the signal's side; null for a neutral strategy, whose legs must name ce or pe */
    public List<ResolvedLeg> resolve(StrategyDefinition definition, Instrument signalInstrument, Side direction) {
        String underlying = optionUnderlying(signalInstrument);
        List<LocalDate> expiries = new ArrayList<>(instruments.weeklyExpiries(underlying));
        if (!expiries.isEmpty() && expiries.get(0).equals(clock.today()) && !clock.nowIst().toLocalTime().isBefore(properties.expiryDayCutoff())) {
            expiries.remove(0); // today's expiry is closed for new positions from the cutoff
        }
        if (expiries.isEmpty()) {
            throw new IllegalStateException("No tradable " + underlying + " option expiry");
        }
        Map<LocalDate, OptionChain> cache = new HashMap<>();
        List<ResolvedLeg> out = new ArrayList<>();
        int sequence = 1;
        for (StrategyDefinition.OptionLeg leg : definition.legs()) {
            LocalDate expiry = expiry(leg.expiry(), expiries);
            OptionChain chain = cache.computeIfAbsent(expiry, e -> chains.chain(underlying, e));
            if (chain.atmStrike() == null) {
                throw new IllegalStateException("No futures or index price for " + underlying + ": cannot choose strikes (" + String.join("; ", chain.notes()) + ")");
            }
            if (direction == null && (leg.option() == StrategyDefinition.OptionSide.DIRECTIONAL || leg.option() == StrategyDefinition.OptionSide.OPPOSITE)) {
                throw new IllegalArgumentException("A neutral signal has no side for a " + leg.option().name().toLowerCase() + " leg");
            }
            OptionType type = switch (leg.option()) {
                case DIRECTIONAL -> direction == Side.BUY ? OptionType.CE : OptionType.PE;
                case OPPOSITE -> direction == Side.BUY ? OptionType.PE : OptionType.CE;
                case CE -> OptionType.CE;
                case PE -> OptionType.PE;
            };
            OptionChain.OptionQuote quote = pick(chain, type, leg.strike());
            Instrument instrument = instruments.findById(quote.instrumentId()).orElseThrow();
            Side side = leg.action() == StrategyDefinition.LegAction.BUY ? Side.BUY : Side.SELL;
            BigDecimal reference = quote.last();
            BigDecimal stop = reference == null || leg.stopPct() == null ? null : level(reference, leg.stopPct(), side == Side.BUY ? -1 : 1, instrument.tickSize());
            BigDecimal target = reference == null || leg.targetPct() == null ? null : level(reference, leg.targetPct(), side == Side.BUY ? 1 : -1, instrument.tickSize());
            out.add(new ResolvedLeg(sequence++, instrument, side, leg.lots() * instrument.lotSize(), reference, stop, target, leg.hedgeFirst()));
        }
        return out;
    }

    private static LocalDate expiry(StrategyDefinition.ExpirySelector selector, List<LocalDate> expiries) {
        return switch (selector) {
            case NEAREST -> expiries.get(0);
            case NEXT -> {
                if (expiries.size() < 2) {
                    throw new IllegalStateException("No next expiry after " + expiries.get(0));
                }
                yield expiries.get(1);
            }
            case MONTHLY -> expiries.stream().filter(e -> e.getYear() == expiries.get(0).getYear() && e.getMonth() == expiries.get(0).getMonth())
                    .max(Comparator.naturalOrder()).orElseThrow();
        };
    }

    /** The quote at the selected strike; OFFSET moves out of the money for the option type, DELTA picks the nearest |delta|. */
    OptionChain.OptionQuote pick(OptionChain chain, OptionType type, StrategyDefinition.StrikeSelector selector) {
        List<OptionChain.Row> available = chain.rows().stream().filter(r -> r.side(type) != null).toList();
        if (available.isEmpty()) {
            throw new IllegalStateException("No " + type + " options in the " + chain.underlying() + " " + chain.expiry() + " chain");
        }
        Optional<OptionChain.Row> row = switch (selector.kind()) {
            case ATM -> nearest(available, chain.atmStrike());
            case OFFSET -> nearest(available, type == OptionType.CE ? chain.atmStrike().add(selector.value()) : chain.atmStrike().subtract(selector.value()));
            case DELTA -> available.stream().min(Comparator.comparingDouble(r -> Math.abs(Math.abs(delta(chain, r, type)) - selector.value().doubleValue())));
        };
        return row.orElseThrow().side(type);
    }

    private static Optional<OptionChain.Row> nearest(List<OptionChain.Row> rows, BigDecimal strike) {
        return rows.stream().min(Comparator.comparing((OptionChain.Row r) -> r.strike().subtract(strike).abs()).thenComparing(OptionChain.Row::strike));
    }

    /** The quote's delta, or one computed at {@code hejje.options.default-volatility} when the strike has no implied volatility. */
    private double delta(OptionChain chain, OptionChain.Row row, OptionType type) {
        OptionChain.OptionQuote q = row.side(type);
        if (q.delta() != null) {
            return q.delta();
        }
        if (chain.forward() == null || chain.yearsToExpiry() == null || chain.yearsToExpiry() <= 0) {
            return type == OptionType.CE ? 0.5 : -0.5;
        }
        return Black76.greeks(type, chain.forward().doubleValue(), row.strike().doubleValue(), chain.yearsToExpiry(), properties.riskFreeRate(),
                properties.defaultVolatility()).delta();
    }

    /** {@code reference × (1 ± pct%)} rounded to the tick, never below one tick. */
    static BigDecimal level(BigDecimal reference, BigDecimal pct, int sign, BigDecimal tick) {
        BigDecimal raw = reference.multiply(BigDecimal.ONE.add(pct.movePointLeft(2).multiply(BigDecimal.valueOf(sign))));
        BigDecimal step = tick == null || tick.signum() <= 0 ? new BigDecimal("0.05") : tick;
        BigDecimal rounded = raw.divide(step, 0, RoundingMode.HALF_UP).multiply(step).setScale(2, RoundingMode.HALF_UP);
        return rounded.max(step.setScale(2, RoundingMode.HALF_UP));
    }
}
