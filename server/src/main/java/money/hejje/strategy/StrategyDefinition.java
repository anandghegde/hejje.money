package money.hejje.strategy;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import money.hejje.common.Money;
import money.hejje.common.Product;
import money.hejje.common.Timeframe;
import money.hejje.strategy.dsl.Condition;

/**
 * A parsed, validated strategy definition (PRD section 10, docs/strategy-dsl.md). Immutable; the YAML it came from is
 * kept alongside in the version row. Defaults are applied at parse time so consumers never see nulls for optional
 * scalars that have a default.
 */
public record StrategyDefinition(
        String name,
        StrategyFamily family,
        String description,
        List<UniverseEntry> universe,
        Timeframe timeframe,
        Direction direction,
        RuleSet entry,
        RuleSet exit,
        StopSpec stop,
        TargetSpec target,
        TrailingStopSpec trailingStop,
        TradeWindow tradeWindow,
        LocalTime forceExitTime,
        int maxTradesPerDay,
        Integer maxHoldingMinutes,
        Integer signalValidityMinutes,
        PositionSizing positionSizing,
        Product product,
        Map<String, RegimePreference> regimePreferences,
        EventRules eventRules,
        RiskOverrides riskOverrides,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) List<OptionLeg> legs,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) CombinedExit combinedExit,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) EntryOrder entryOrder) {

    public StrategyDefinition {
        universe = List.copyOf(universe);
        legs = legs == null ? List.of() : List.copyOf(legs); // empty legs and a null combined exit are left out of the JSON, so existing hashes stay
        regimePreferences = Map.copyOf(regimePreferences);
    }

    /**
     * The side taken on the underlying. {@code NEUTRAL} (options strategies only, plan M6.4) takes none: the legs define the
     * exposure and the stop becomes a symmetric band around the underlying's price at the signal.
     */
    public enum Direction { LONG, SHORT, BOTH, NEUTRAL }

    /**
     * One option leg of an options strategy (plan M5.4, docs/strategy-dsl.md "Options legs"): what is traded when the
     * strategy signals on its underlying. Stops and targets are percent of the leg's entry premium.
     */
    public record OptionLeg(LegAction action, OptionSide option, StrikeSelector strike, ExpirySelector expiry, int lots, BigDecimal stopPct,
            BigDecimal targetPct, boolean hedgeFirst) {}

    public enum LegAction { BUY, SELL }

    /** DIRECTIONAL: CE on a long signal, PE on a short one; OPPOSITE: the reverse; CE / PE: fixed. */
    public enum OptionSide { DIRECTIONAL, OPPOSITE, CE, PE }

    /** NEAREST: the first expiry still tradable today; NEXT: the one after; MONTHLY: the last expiry of that month. */
    public enum ExpirySelector { NEAREST, NEXT, MONTHLY }

    /** ATM; OFFSET points from ATM (positive = out of the money for the leg's option type); or the strike whose |delta| is nearest DELTA. */
    public record StrikeSelector(StrikeKind kind, BigDecimal value) {
        public static final StrikeSelector ATM = new StrikeSelector(StrikeKind.ATM, null);
    }

    public enum StrikeKind { ATM, OFFSET, DELTA }

    /** Exits on the combined P&L of all legs (rupees); either may be null. */
    public record CombinedExit(Money stopRupees, Money targetRupees) {}

    public enum RuleMode { ALL, ANY }

    /** {@code all:} or {@code any:} list of conditions. */
    public record RuleSet(RuleMode mode, List<Condition> conditions) {
        public RuleSet {
            conditions = List.copyOf(conditions);
        }
    }

    /**
     * One universe line. {@code SYMBOL} carries a canonical Hejje symbol; {@code ALIAS} a bare name such as
     * {@code NIFTY} resolved through the configured aliases; {@code NEAREST_FUTURE} and {@code INDEX} are selectors.
     */
    public record UniverseEntry(UniverseKind kind, String value) {
        public String text() {
            return switch (kind) {
                case SYMBOL, ALIAS -> value;
                case NEAREST_FUTURE -> "nearest_future: " + value;
                case INDEX -> "index: " + value;
            };
        }
    }

    public enum UniverseKind { SYMBOL, ALIAS, NEAREST_FUTURE, INDEX }

    public enum StopType {
        OPENING_RANGE_LOW, OPENING_RANGE_HIGH, ATR_MULTIPLE, PERCENT, POINTS, SWING_LOW, SWING_HIGH, PREV_DAY_LOW, PREV_DAY_HIGH;

        /** True for stops that need a {@code value}. */
        public boolean needsValue() {
            return this == ATR_MULTIPLE || this == PERCENT || this == POINTS;
        }

        /** True for stops that only make sense for one direction. */
        public boolean isDirectional() {
            return !needsValue();
        }

        /** True when this stop type belongs below the entry (a long stop). */
        public boolean isLongSide() {
            return this == OPENING_RANGE_LOW || this == SWING_LOW || this == PREV_DAY_LOW;
        }
    }

    public record StopSpec(StopType type, BigDecimal value) {}

    public enum TargetType {
        RISK_MULTIPLE, POINTS, PERCENT, VWAP, NONE;

        public boolean needsValue() {
            return this == RISK_MULTIPLE || this == POINTS || this == PERCENT;
        }
    }

    public record TargetSpec(TargetType type, BigDecimal value) {
        public static final TargetSpec NONE = new TargetSpec(TargetType.NONE, null);
    }

    public enum TrailingType { ATR_MULTIPLE, PERCENT, BREAKEVEN_AT_R }

    public record TrailingStopSpec(TrailingType type, BigDecimal value) {}

    public record TradeWindow(LocalTime start, LocalTime end) {}

    public enum SizingType { RISK_BASED }

    /** Either {@code riskRupees} or {@code riskPercentOfCapital} may be set; neither means the deployment decides. */
    public record PositionSizing(SizingType type, Money riskRupees, BigDecimal riskPercentOfCapital) {
        public static final PositionSizing DEFAULT = new PositionSizing(SizingType.RISK_BASED, null, null);
    }

    public enum RegimePreference { PREFERRED, NEUTRAL, AVOID }

    public enum EventAction { BLOCK, CAUTION, ALLOW }

    public record EventRules(Integer highRiskEventWithinMinutes, EventAction action) {
        public static final EventRules DEFAULT = new EventRules(null, EventAction.ALLOW);
    }

    public record RiskOverrides(BigDecimal minRewardRisk, Integer maxQuantity) {
        public static final RiskOverrides NONE = new RiskOverrides(null, null);
    }

    /** The entry order as written (plan M9.8); null means {@link EntryOrder#MARKET}, so older definitions hash as before. */
    public EntryOrder entryOrderOrMarket() {
        return entryOrder == null ? EntryOrder.MARKET : entryOrder;
    }

    public enum EntryOrderType { MARKET, LIMIT_TOUCH }

    /**
     * How the entry is placed (plan M9.8, docs/signals.md "Passive entries"). {@code LIMIT_TOUCH}: a limit at the best
     * bid (long) or ask (short), moved to the new touch at most {@code maxRequotes} times and never more than
     * {@code maxChaseBps} beyond the signal's price, cancelled after {@code cancelAfterSeconds}.
     */
    public record EntryOrder(EntryOrderType type, int maxRequotes, int cancelAfterSeconds, BigDecimal maxChaseBps) {
        public static final EntryOrder MARKET = new EntryOrder(EntryOrderType.MARKET, 0, 0, BigDecimal.ZERO);

        public boolean passive() {
            return type == EntryOrderType.LIMIT_TOUCH;
        }
    }
}
