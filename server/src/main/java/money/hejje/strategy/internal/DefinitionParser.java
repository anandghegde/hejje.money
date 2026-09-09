package money.hejje.strategy.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import money.hejje.common.Money;
import money.hejje.common.Product;
import money.hejje.common.Timeframe;
import money.hejje.instruments.HejjeSymbol;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.StrategyDefinition.Direction;
import money.hejje.strategy.StrategyDefinition.EventAction;
import money.hejje.strategy.StrategyDefinition.EventRules;
import money.hejje.strategy.StrategyDefinition.PositionSizing;
import money.hejje.strategy.StrategyDefinition.RegimePreference;
import money.hejje.strategy.StrategyDefinition.RiskOverrides;
import money.hejje.strategy.StrategyDefinition.RuleMode;
import money.hejje.strategy.StrategyDefinition.RuleSet;
import money.hejje.strategy.StrategyDefinition.SizingType;
import money.hejje.strategy.StrategyDefinition.StopSpec;
import money.hejje.strategy.StrategyDefinition.StopType;
import money.hejje.strategy.StrategyDefinition.TargetSpec;
import money.hejje.strategy.StrategyDefinition.TargetType;
import money.hejje.strategy.StrategyDefinition.TradeWindow;
import money.hejje.strategy.StrategyDefinition.TrailingStopSpec;
import money.hejje.strategy.StrategyDefinition.TrailingType;
import money.hejje.strategy.StrategyDefinition.UniverseEntry;
import money.hejje.strategy.StrategyDefinition.UniverseKind;
import money.hejje.strategy.StrategyFamily;
import money.hejje.strategy.StrategyValidationException;
import money.hejje.strategy.ValidationError;
import money.hejje.strategy.dsl.Condition;
import money.hejje.strategy.dsl.ConditionParser;
import money.hejje.strategy.dsl.ParseException;
import org.springframework.stereotype.Component;

/**
 * Structural parsing of a YAML definition into a {@link StrategyDefinition} (docs/strategy-schema.json). Every problem
 * is collected as a {@code {path, message}} error and reported together; semantic rules live in
 * {@link StrategyValidator}. Unknown keys are errors so that typos never silently disable a rule.
 */
@Component
public class DefinitionParser {

    static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");
    static final LocalTime DEFAULT_FORCE_EXIT = LocalTime.of(15, 10);

    private static final List<String> TOP_LEVEL_KEYS = List.of("name", "version", "family", "description", "universe", "timeframe",
            "direction", "entry", "exit", "stop", "target", "trailing_stop", "trade_window", "force_exit_time",
            "max_trades_per_day", "max_holding_minutes", "signal_validity_minutes", "position_sizing", "product",
            "regime_preferences", "event_rules", "risk_overrides");

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    /** Parses the YAML text into a definition, applying defaults. Throws with every structural error found. */
    public StrategyDefinition parse(String yamlText) {
        Map<String, Object> root = readTree(yamlText);
        Errors errors = new Errors();
        StrategyDefinition definition = map(new Node(root, "", errors));
        if (!errors.list.isEmpty()) {
            throw new StrategyValidationException(errors.list);
        }
        return definition;
    }

    /** Reads the YAML document as a plain map (insertion ordered). Throws a validation exception on malformed YAML. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readTree(String yamlText) {
        if (yamlText == null || yamlText.isBlank()) {
            throw new StrategyValidationException(List.of(new ValidationError("", "Definition is empty")));
        }
        Object tree;
        try {
            tree = yaml.readValue(yamlText, Object.class);
        } catch (Exception e) {
            String message = e.getMessage() == null ? "Malformed YAML" : e.getMessage().split("\n")[0];
            throw new StrategyValidationException(List.of(new ValidationError("", "Malformed YAML: " + message)));
        }
        if (!(tree instanceof Map<?, ?> map)) {
            throw new StrategyValidationException(List.of(new ValidationError("", "Definition must be a YAML mapping")));
        }
        return new LinkedHashMap<>((Map<String, Object>) map);
    }

    /** Serialises a tree back to YAML (used by clone). */
    public String writeTree(Map<String, Object> tree) {
        try {
            return yaml.writeValueAsString(tree);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise definition", e);
        }
    }

    private StrategyDefinition map(Node root) {
        root.rejectUnknownKeys(TOP_LEVEL_KEYS);
        String name = root.requiredString("name");
        if (name != null && !NAME.matcher(name).matches()) {
            root.error("name", "must match " + NAME.pattern() + " (lower case, digits and underscores)");
        }
        StrategyFamily family = root.enumValue("family", StrategyFamily.class, StrategyFamily.TREND);
        String description = root.string("description");
        List<UniverseEntry> universe = universe(root);
        Timeframe timeframe = timeframe(root);
        Direction direction = root.enumValue("direction", Direction.class, null, true);
        RuleSet entry = ruleSet(root.requiredMap("entry"));
        RuleSet exit = root.has("exit") ? ruleSet(root.map("exit")) : null;
        StopSpec stop = stop(root.requiredMap("stop"));
        TargetSpec target = root.has("target") ? target(root.map("target")) : TargetSpec.NONE;
        TrailingStopSpec trailing = root.has("trailing_stop") ? trailing(root.map("trailing_stop")) : null;
        LocalTime forceExit = root.time("force_exit_time", DEFAULT_FORCE_EXIT);
        TradeWindow window = root.has("trade_window") ? window(root.map("trade_window"))
                : new TradeWindow(LocalTime.of(9, 15), forceExit == null ? DEFAULT_FORCE_EXIT : forceExit);
        int maxTrades = root.integer("max_trades_per_day", 1);
        Integer maxHolding = root.integer("max_holding_minutes", null);
        Integer validity = root.integer("signal_validity_minutes", null);
        PositionSizing sizing = root.has("position_sizing") ? sizing(root.map("position_sizing")) : PositionSizing.DEFAULT;
        Product product = root.enumValue("product", Product.class, Product.MIS);
        Map<String, RegimePreference> regimes = regimes(root);
        EventRules events = root.has("event_rules") ? events(root.map("event_rules")) : EventRules.DEFAULT;
        RiskOverrides overrides = root.has("risk_overrides") ? overrides(root.map("risk_overrides")) : RiskOverrides.NONE;
        if (!root.errors.list.isEmpty()) {
            return null;
        }
        return new StrategyDefinition(name, family, description, universe, timeframe, direction, entry, exit, stop, target,
                trailing, window, forceExit, maxTrades, maxHolding, validity, sizing, product, regimes, events, overrides);
    }

    private List<UniverseEntry> universe(Node root) {
        List<Node> items = root.requiredList("universe");
        List<UniverseEntry> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        if (items.isEmpty()) {
            root.error("universe", "must list at least one instrument");
        }
        for (Node item : items) {
            if (item.value instanceof String s) {
                String text = s.trim();
                if (text.isEmpty()) {
                    item.error("", "must not be blank");
                } else if (text.contains(":")) {
                    try {
                        out.add(new UniverseEntry(UniverseKind.SYMBOL, HejjeSymbol.parse(text).format()));
                    } catch (IllegalArgumentException e) {
                        item.error("", e.getMessage());
                    }
                } else {
                    out.add(new UniverseEntry(UniverseKind.ALIAS, text.toUpperCase(Locale.ROOT)));
                }
            } else if (item.value instanceof Map<?, ?> m && m.size() == 1) {
                String key = String.valueOf(m.keySet().iterator().next());
                Object v = m.values().iterator().next();
                if (!(v instanceof String s) || s.isBlank()) {
                    item.error(key, "must be a name");
                    continue;
                }
                switch (key) {
                    case "nearest_future" -> out.add(new UniverseEntry(UniverseKind.NEAREST_FUTURE, s.trim().toUpperCase(Locale.ROOT)));
                    case "index" -> out.add(new UniverseEntry(UniverseKind.INDEX, s.trim().toUpperCase(Locale.ROOT)));
                    case "symbol" -> {
                        try {
                            out.add(new UniverseEntry(UniverseKind.SYMBOL, HejjeSymbol.parse(s).format()));
                        } catch (IllegalArgumentException e) {
                            item.error(key, e.getMessage());
                        }
                    }
                    default -> item.error(key, "unknown selector; use symbol, nearest_future or index");
                }
            } else {
                item.error("", "must be a symbol string or a one-key selector (symbol / nearest_future / index)");
            }
        }
        return out;
    }

    private Timeframe timeframe(Node root) {
        String text = root.requiredString("timeframe");
        if (text == null) {
            return null;
        }
        Timeframe tf = switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "1m" -> Timeframe.M1;
            case "3m" -> Timeframe.M3;
            case "5m" -> Timeframe.M5;
            case "15m" -> Timeframe.M15;
            case "1h", "60m" -> Timeframe.H1;
            case "1d" -> Timeframe.D1;
            default -> null;
        };
        if (tf == null) {
            root.error("timeframe", "must be one of 1m, 3m, 5m, 15m, 1h, 1d");
        }
        return tf;
    }

    private RuleSet ruleSet(Node node) {
        if (node == null) {
            return null;
        }
        node.rejectUnknownKeys(List.of("all", "any"));
        boolean all = node.has("all");
        boolean any = node.has("any");
        if (all == any) {
            node.error("", "must contain exactly one of all: or any:");
            return null;
        }
        String key = all ? "all" : "any";
        List<Node> items = node.requiredList(key);
        if (items == null) {
            return null;
        }
        if (items.isEmpty()) {
            node.error(key, "must list at least one condition");
        }
        List<Condition> conditions = new ArrayList<>();
        for (Node item : items) {
            if (!(item.value instanceof String s)) {
                item.error("", "must be a condition string");
                continue;
            }
            try {
                conditions.add(ConditionParser.parse(s));
            } catch (ParseException e) {
                item.error("", e.getMessage());
            }
        }
        return new RuleSet(all ? RuleMode.ALL : RuleMode.ANY, conditions);
    }

    private StopSpec stop(Node node) {
        if (node == null) {
            return null;
        }
        node.rejectUnknownKeys(List.of("type", "value"));
        StopType type = node.enumValue("type", StopType.class, null, true);
        BigDecimal value = node.decimal("value");
        if (type != null) {
            if (type.needsValue() && value == null) {
                node.error("value", "is required for stop type " + type.name().toLowerCase(Locale.ROOT));
            }
            if (!type.needsValue() && value != null) {
                node.error("value", "is not used by stop type " + type.name().toLowerCase(Locale.ROOT));
            }
            if (value != null && value.signum() <= 0) {
                node.error("value", "must be positive");
            }
        }
        return new StopSpec(type, value);
    }

    private TargetSpec target(Node node) {
        if (node == null) {
            return null;
        }
        node.rejectUnknownKeys(List.of("type", "value"));
        TargetType type = node.enumValue("type", TargetType.class, null, true);
        BigDecimal value = node.decimal("value");
        if (type != null) {
            if (type.needsValue() && value == null) {
                node.error("value", "is required for target type " + type.name().toLowerCase(Locale.ROOT));
            }
            if (!type.needsValue() && value != null) {
                node.error("value", "is not used by target type " + type.name().toLowerCase(Locale.ROOT));
            }
            if (value != null && value.signum() <= 0) {
                node.error("value", "must be positive");
            }
        }
        return new TargetSpec(type, value);
    }

    private TrailingStopSpec trailing(Node node) {
        if (node == null) {
            return null;
        }
        node.rejectUnknownKeys(List.of("type", "value"));
        TrailingType type = node.enumValue("type", TrailingType.class, null, true);
        BigDecimal value = node.decimal("value");
        if (value == null) {
            node.error("value", "is required");
        } else if (value.signum() <= 0) {
            node.error("value", "must be positive");
        }
        return new TrailingStopSpec(type, value);
    }

    private TradeWindow window(Node node) {
        if (node == null) {
            return null;
        }
        node.rejectUnknownKeys(List.of("start", "end"));
        LocalTime start = node.time("start", null);
        LocalTime end = node.time("end", null);
        if (start == null && !node.has("start")) {
            node.error("start", "is required");
        }
        if (end == null && !node.has("end")) {
            node.error("end", "is required");
        }
        return new TradeWindow(start, end);
    }

    private PositionSizing sizing(Node node) {
        if (node == null) {
            return null;
        }
        node.rejectUnknownKeys(List.of("type", "risk_rupees", "risk_percent_of_capital"));
        SizingType type = node.enumValue("type", SizingType.class, SizingType.RISK_BASED);
        BigDecimal rupees = node.decimal("risk_rupees");
        BigDecimal pct = node.decimal("risk_percent_of_capital");
        if (rupees != null && pct != null) {
            node.error("", "set either risk_rupees or risk_percent_of_capital, not both");
        }
        if (rupees != null && rupees.signum() <= 0) {
            node.error("risk_rupees", "must be positive");
        }
        if (pct != null && (pct.signum() <= 0 || pct.compareTo(BigDecimal.valueOf(100)) > 0)) {
            node.error("risk_percent_of_capital", "must be between 0 and 100");
        }
        Money money = null;
        if (rupees != null && rupees.signum() > 0) {
            try {
                money = Money.of(rupees);
            } catch (IllegalArgumentException e) {
                node.error("risk_rupees", e.getMessage());
            }
        }
        return new PositionSizing(type, money, pct);
    }

    private Map<String, RegimePreference> regimes(Node root) {
        Map<String, RegimePreference> out = new LinkedHashMap<>();
        if (!root.has("regime_preferences")) {
            return out;
        }
        Node node = root.map("regime_preferences");
        if (node == null) {
            return out;
        }
        for (String key : node.keys()) {
            if (!key.matches("^[a-z][a-z_]*$")) {
                node.error(key, "regime names are lower case words such as trending, ranging, volatile");
                continue;
            }
            RegimePreference pref = node.enumValue(key, RegimePreference.class, null, true);
            if (pref != null) {
                out.put(key, pref);
            }
        }
        return out;
    }

    private EventRules events(Node node) {
        if (node == null) {
            return null;
        }
        node.rejectUnknownKeys(List.of("high_risk_event_within_minutes", "action"));
        Integer minutes = node.integer("high_risk_event_within_minutes", null);
        EventAction action = node.enumValue("action", EventAction.class, EventAction.ALLOW);
        if (minutes != null && minutes < 0) {
            node.error("high_risk_event_within_minutes", "must not be negative");
        }
        if (minutes == null && action != EventAction.ALLOW) {
            node.error("high_risk_event_within_minutes", "is required when action is " + action.name().toLowerCase(Locale.ROOT));
        }
        return new EventRules(minutes, action);
    }

    private RiskOverrides overrides(Node node) {
        if (node == null) {
            return null;
        }
        node.rejectUnknownKeys(List.of("min_reward_risk", "max_quantity"));
        BigDecimal rr = node.decimal("min_reward_risk");
        Integer maxQty = node.integer("max_quantity", null);
        if (rr != null && rr.signum() <= 0) {
            node.error("min_reward_risk", "must be positive");
        }
        if (maxQty != null && maxQty < 1) {
            node.error("max_quantity", "must be at least 1");
        }
        return new RiskOverrides(rr, maxQty);
    }

    /** Error collector shared by all nodes of one document. */
    static final class Errors {
        final List<ValidationError> list = new ArrayList<>();
    }

    /** A YAML value plus its path; typed accessors record errors instead of throwing. */
    static final class Node {
        final Object value;
        final String path;
        final Errors errors;

        Node(Object value, String path, Errors errors) {
            this.value = value;
            this.path = path;
            this.errors = errors;
        }

        private String child(String key) {
            return path.isEmpty() ? key : (key.isEmpty() ? path : path + "." + key);
        }

        void error(String key, String message) {
            errors.list.add(new ValidationError(child(key), message));
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> asMap() {
            return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }

        boolean has(String key) {
            return asMap().containsKey(key) && asMap().get(key) != null;
        }

        List<String> keys() {
            return new ArrayList<>(asMap().keySet());
        }

        void rejectUnknownKeys(List<String> known) {
            for (String key : asMap().keySet()) {
                if (!known.contains(key)) {
                    error(key, "unknown key");
                }
            }
        }

        String string(String key) {
            Object v = asMap().get(key);
            if (v == null) {
                return null;
            }
            if (v instanceof Map || v instanceof List) {
                error(key, "must be a text value");
                return null;
            }
            return String.valueOf(v);
        }

        String requiredString(String key) {
            String s = string(key);
            if (s == null && !asMap().containsKey(key)) {
                error(key, "is required");
            } else if (s != null && s.isBlank()) {
                error(key, "must not be blank");
                return null;
            }
            return s;
        }

        Integer integer(String key, Integer defaultValue) {
            Object v = asMap().get(key);
            if (v == null) {
                return defaultValue;
            }
            if (v instanceof Integer i) {
                return i;
            }
            if (v instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                return l.intValue();
            }
            if (v instanceof String s && s.trim().matches("-?\\d+")) {
                try {
                    return Integer.parseInt(s.trim());
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
            error(key, "must be a whole number");
            return defaultValue;
        }

        BigDecimal decimal(String key) {
            Object v = asMap().get(key);
            if (v == null) {
                return null;
            }
            if (v instanceof Double d) {
                return BigDecimal.valueOf(d);
            }
            if (v instanceof Float f) {
                return BigDecimal.valueOf(f.doubleValue());
            }
            if (v instanceof Number n) {
                return new BigDecimal(n.toString());
            }
            if (v instanceof String s) {
                try {
                    return new BigDecimal(s.trim());
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
            error(key, "must be a number");
            return null;
        }

        LocalTime time(String key, LocalTime defaultValue) {
            Object v = asMap().get(key);
            if (v == null) {
                return defaultValue;
            }
            if (v instanceof Integer || v instanceof Long) {
                error(key, "must be a quoted time such as \"09:30\"");
                return null;
            }
            try {
                return LocalTime.parse(String.valueOf(v).trim());
            } catch (DateTimeParseException e) {
                error(key, "must be a time such as \"09:30\"");
                return null;
            }
        }

        <E extends Enum<E>> E enumValue(String key, Class<E> type, E defaultValue) {
            return enumValue(key, type, defaultValue, false);
        }

        <E extends Enum<E>> E enumValue(String key, Class<E> type, E defaultValue, boolean required) {
            Object v = asMap().get(key);
            if (v == null) {
                if (required) {
                    error(key, "is required");
                }
                return defaultValue;
            }
            String text = String.valueOf(v).trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(text)) {
                    return constant;
                }
            }
            List<String> allowed = new ArrayList<>();
            for (E constant : type.getEnumConstants()) {
                allowed.add(constant.name().toLowerCase(Locale.ROOT));
            }
            error(key, "must be one of " + String.join(", ", allowed));
            return defaultValue;
        }

        Node map(String key) {
            Object v = asMap().get(key);
            if (v == null) {
                return null;
            }
            if (!(v instanceof Map)) {
                error(key, "must be a mapping");
                return null;
            }
            return new Node(v, child(key), errors);
        }

        Node requiredMap(String key) {
            if (!has(key)) {
                error(key, "is required");
                return null;
            }
            return map(key);
        }

        List<Node> list(String key) {
            Object v = asMap().get(key);
            if (v == null) {
                return null;
            }
            if (!(v instanceof List<?> list)) {
                error(key, "must be a list");
                return null;
            }
            List<Node> out = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                out.add(new Node(list.get(i), child(key) + "[" + i + "]", errors));
            }
            return out;
        }

        List<Node> requiredList(String key) {
            if (!has(key)) {
                error(key, "is required");
                return null;
            }
            return list(key);
        }
    }
}
