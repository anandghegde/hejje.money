package money.hejje.options;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import money.hejje.common.OptionType;
import money.hejje.common.time.HejjeClock;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.MarketService;
import money.hejje.market.QuoteSnapshot;
import org.springframework.stereotype.Service;

/**
 * Option chains with IV and greeks (Black-76 on the nearest future expiring on or after the options, else the index spot
 * as a proxy), ATM strike, put/call ratios and max pain (plan M5.4). Requesting a chain subscribes its instruments to
 * market data in FULL mode (open interest) so the next request has live quotes.
 */
@Service
public class OptionChainService {

    private final InstrumentService instruments;
    private final MarketService market;
    private final OptionsProperties properties;
    private final HejjeClock clock;

    OptionChainService(InstrumentService instruments, MarketService market, OptionsProperties properties, HejjeClock clock) {
        this.instruments = instruments;
        this.market = market;
        this.properties = properties;
        this.clock = clock;
    }

    public List<LocalDate> expiries(String underlying) {
        return instruments.weeklyExpiries(underlying);
    }

    record Forward(BigDecimal price, String source, UUID instrumentId) {}

    public OptionChain chain(String underlying, LocalDate expiry) {
        String u = underlying.trim().toUpperCase();
        List<Instrument> options = instruments.optionChain(u, expiry);
        if (options.isEmpty()) {
            throw new NoSuchElementException("No " + u + " options expire on " + expiry);
        }
        List<String> notes = new ArrayList<>();
        Forward forward = forward(u, expiry, notes);
        Set<UUID> ids = options.stream().map(Instrument::id).collect(Collectors.toSet());
        if (forward.instrumentId() != null) {
            ids.add(forward.instrumentId());
        }
        boolean priced = forward.price() != null;
        try {
            market.subscribeFull(ids); // OI (PCR, max pain) only comes with full-mode ticks
        } catch (RuntimeException e) {
            notes.add("market data subscription failed: " + e.getMessage());
        }
        Instant now = clock.now();
        double years = Duration.between(now, expiry.atTime(HejjeClock.SESSION_CLOSE).atZone(clock.zone()).toInstant()).toSeconds() / (365.0 * 86_400);
        if (years <= 0) {
            notes.add("expired: no implied volatility or greeks");
        }
        Map<UUID, QuoteSnapshot> quotes = market.quotes(options.stream().map(Instrument::id).collect(Collectors.toSet()));
        TreeMap<BigDecimal, OptionChain.OptionQuote[]> byStrike = new TreeMap<>();
        for (Instrument o : options) {
            QuoteSnapshot q = quotes.get(o.id());
            BigDecimal last = q == null ? null : q.lastPrice();
            Double iv = null;
            Black76.Greeks g = null;
            if (priced && years > 0 && last != null && last.signum() > 0) {
                iv = Black76.impliedVol(o.optionType(), forward.price().doubleValue(), o.strike().doubleValue(), years, properties.riskFreeRate(), last.doubleValue());
                if (iv != null) {
                    g = Black76.greeks(o.optionType(), forward.price().doubleValue(), o.strike().doubleValue(), years, properties.riskFreeRate(), iv);
                }
            }
            OptionChain.OptionQuote quote = new OptionChain.OptionQuote(o.id(), o.hejjeSymbol().format(), o.lotSize(), last, q == null ? null : q.bid(),
                    q == null ? null : q.ask(), q == null ? 0 : q.oi(), q == null ? 0 : q.volume(), iv, g == null ? null : g.delta(), g == null ? null : g.gamma(),
                    g == null ? null : g.vega(), g == null ? null : g.theta(), q == null || q.stale());
            OptionChain.OptionQuote[] pair = byStrike.computeIfAbsent(o.strike().stripTrailingZeros(), k -> new OptionChain.OptionQuote[2]);
            pair[o.optionType() == OptionType.CE ? 0 : 1] = quote;
        }
        List<OptionChain.Row> rows = new ArrayList<>();
        byStrike.forEach((strike, pair) -> rows.add(new OptionChain.Row(strike.setScale(2), pair[0], pair[1])));
        BigDecimal atm = !priced ? null : rows.stream().map(OptionChain.Row::strike)
                .min(Comparator.comparing(k -> k.subtract(forward.price()).abs())).orElse(null);
        long callOi = rows.stream().filter(r -> r.call() != null).mapToLong(r -> r.call().oi()).sum();
        long putOi = rows.stream().filter(r -> r.put() != null).mapToLong(r -> r.put().oi()).sum();
        long callVol = rows.stream().filter(r -> r.call() != null).mapToLong(r -> r.call().volume()).sum();
        long putVol = rows.stream().filter(r -> r.put() != null).mapToLong(r -> r.put().volume()).sum();
        return new OptionChain(u, expiry, now, forward.price(), forward.source(), years, atm,
                callOi == 0 ? null : (double) putOi / callOi, callVol == 0 ? null : (double) putVol / callVol, maxPain(rows), rows, notes);
    }

    /** The strike minimising the total expiry payout to option holders, over the chain's strikes; null without open interest. */
    static BigDecimal maxPain(List<OptionChain.Row> rows) {
        long totalOi = rows.stream().mapToLong(r -> (r.call() == null ? 0 : r.call().oi()) + (r.put() == null ? 0 : r.put().oi())).sum();
        if (totalOi == 0) {
            return null;
        }
        BigDecimal best = null;
        BigDecimal bestPayout = null;
        for (OptionChain.Row settle : rows) {
            BigDecimal payout = BigDecimal.ZERO;
            for (OptionChain.Row r : rows) {
                if (r.call() != null && settle.strike().compareTo(r.strike()) > 0) {
                    payout = payout.add(settle.strike().subtract(r.strike()).multiply(BigDecimal.valueOf(r.call().oi())));
                }
                if (r.put() != null && r.strike().compareTo(settle.strike()) > 0) {
                    payout = payout.add(r.strike().subtract(settle.strike()).multiply(BigDecimal.valueOf(r.put().oi())));
                }
            }
            if (bestPayout == null || payout.compareTo(bestPayout) < 0) {
                bestPayout = payout;
                best = settle.strike();
            }
        }
        return best;
    }

    /** The futures price for the model (nearest future expiring on or after the options), else the index spot; the price is null when neither is known. */
    private Forward forward(String underlying, LocalDate expiry, List<String> notes) {
        Optional<Instrument> future = instruments.futures(underlying).stream()
                .filter(i -> i.active() && i.expiry() != null && !i.expiry().isBefore(expiry)).min(Comparator.comparing(Instrument::expiry));
        if (future.isPresent()) {
            Optional<BigDecimal> price = market.lastPrice(future.get().id());
            if (price.isPresent()) {
                return new Forward(price.get(), future.get().hejjeSymbol().format(), future.get().id());
            }
            notes.add("no price yet for " + future.get().hejjeSymbol().format());
        }
        String index = properties.underlyings().entrySet().stream().filter(e -> e.getValue().equals(underlying)).map(Map.Entry::getKey).findFirst().orElse(null);
        Optional<Instrument> idx = index == null ? Optional.empty() : instruments.resolve("INDEX:" + index);
        Optional<BigDecimal> spot = idx.flatMap(i -> market.lastPrice(i.id()));
        if (spot.isPresent()) {
            notes.add("forward approximated by the index spot INDEX:" + index);
            return new Forward(spot.get(), "INDEX:" + index, idx.get().id());
        }
        notes.add("no futures or index price: no implied volatility, greeks or ATM strike");
        return new Forward(null, null, future.map(Instrument::id).orElse(idx.map(Instrument::id).orElse(null)));
    }
}
