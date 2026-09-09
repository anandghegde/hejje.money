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
        RiskOverrides riskOverrides) {

    public StrategyDefinition {
        universe = List.copyOf(universe);
        regimePreferences = Map.copyOf(regimePreferences);
    }

    public enum Direction { LONG, SHORT, BOTH }

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
}
