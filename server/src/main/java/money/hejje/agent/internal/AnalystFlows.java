package money.hejje.agent.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import money.hejje.agent.AgentToolService;
import money.hejje.agent.ToolContext;
import money.hejje.agent.ToolResult;
import org.springframework.stereotype.Component;

/**
 * Canned analyst flows (plan M4.3): deterministic tool compositions whose outputs are rendered into a templated
 * evidence block handed to the model with the question. Detected from the question or chosen explicitly.
 * <ul>
 *   <li>{@code why_ranked_first} — rankings, then the chosen row's score breakdown and rules;</li>
 *   <li>{@code compare} — two strategies (latest versions) or two versions of one strategy;</li>
 *   <li>{@code working_today} — rankings plus today's P&L by strategy;</li>
 *   <li>{@code losses} — "what lost me money this month": loss attribution, slippage, rule adherence and, for the largest
 *   family × trend loss bucket, a counterfactual kept apart as SIMULATED (PRD 57).</li>
 * </ul>
 */
@Component
public class AnalystFlows {

    public enum Flow {
        WHY_RANKED_FIRST, COMPARE, WORKING_TODAY, LOSSES;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Optional<Flow> of(String id) {
            return Arrays.stream(values()).filter(f -> f.id().equalsIgnoreCase(id.trim())).findFirst();
        }
    }

    public record Detected(Flow flow, List<String> args) {}

    public static final String EVIDENCE_HEADER = "Evidence (fetched by Hejje for this question from the tools in brackets):";

    private static final Pattern WHY = Pattern.compile("(?i)\\bwhy\\s+is\\s+(.+?)\\s+(?:ranked|rated|placed)\\s+(?:first|top|#?1|number\\s+one)\\b");
    private static final Pattern COMPARE = Pattern.compile("(?i)\\bcompare\\s+(.+?)\\s+(?:and|vs\\.?|versus|with|to)\\s+(.+?)\\s*[?.!]*$");
    private static final Pattern WORKING = Pattern.compile("(?i)\\bwhat(?:'s|\\s+is)\\s+working\\s+today\\b");
    private static final Pattern LOSSES = Pattern.compile(
            "(?i)\\b(?:what|which)\\b[^?]*\\b(?:lost|losing|cost)\\b[^?]*\\bmoney\\b|\\bwhy\\s+am\\s+i\\s+losing\\b|\\bwhat\\s+caused\\s+(?:most\\s+of\\s+)?my\\s+losses\\b|\\bwhere\\s+did\\s+i\\s+lose\\b");
    private static final Pattern VERSIONED = Pattern.compile("(?i)^(.*?)\\s*v(\\d+)$");

    private final AgentToolService tools;
    private final ObjectMapper json;

    AnalystFlows(AgentToolService tools, ObjectMapper json) {
        this.tools = tools;
        this.json = json;
    }

    public Optional<Detected> detect(String question, String flow) {
        String q = question.trim();
        if (flow != null && !flow.isBlank()) {
            Flow f = Flow.of(flow).orElseThrow(() -> new IllegalArgumentException("Unknown flow " + flow + " (why_ranked_first, compare, working_today)"));
            List<String> args = match(f, q);
            return Optional.of(new Detected(f, args == null ? List.of() : args));
        }
        for (Flow f : Flow.values()) {
            List<String> args = match(f, q);
            if (args != null) {
                return Optional.of(new Detected(f, args));
            }
        }
        return Optional.empty();
    }

    private static List<String> match(Flow f, String q) {
        Matcher m = switch (f) {
            case WHY_RANKED_FIRST -> WHY.matcher(q);
            case COMPARE -> COMPARE.matcher(q);
            case WORKING_TODAY -> WORKING.matcher(q);
            case LOSSES -> LOSSES.matcher(q);
        };
        if (!m.find()) {
            return null;
        }
        List<String> args = new ArrayList<>();
        for (int i = 1; i <= m.groupCount(); i++) {
            args.add(m.group(i).trim());
        }
        return args;
    }

    /** Runs the flow's tools (each reported to {@code onTool}) and returns the evidence block. */
    public String run(Detected d, ToolContext ctx, Consumer<ToolResult> onTool) {
        StringBuilder e = new StringBuilder(EVIDENCE_HEADER).append('\n');
        switch (d.flow()) {
            case WHY_RANKED_FIRST -> whyRankedFirst(d.args().isEmpty() ? null : d.args().get(0), ctx, onTool, e);
            case COMPARE -> compare(d.args(), ctx, onTool, e);
            case WORKING_TODAY -> workingToday(ctx, onTool, e);
            case LOSSES -> losses(ctx, onTool, e);
        }
        return e.toString();
    }

    private JsonNode call(String tool, Map<String, Object> input, ToolContext ctx, Consumer<ToolResult> onTool, StringBuilder e) {
        ToolResult r = tools.invoke(tool, json.valueToTree(input), ctx);
        onTool.accept(r);
        if (!r.ok()) {
            e.append("- ").append(tool).append(" was ").append(r.status()).append(": ").append(r.error()).append(" [").append(tool).append("]\n");
            return null;
        }
        return r.output();
    }

    private void whyRankedFirst(String wanted, ToolContext ctx, Consumer<ToolResult> onTool, StringBuilder e) {
        JsonNode rankings = call("get_strategy_rankings", Map.of("limit", 5), ctx, onTool, e);
        if (rankings == null) {
            return;
        }
        JsonNode ranked = rankings.path("ranked");
        if (ranked.isEmpty()) {
            e.append("- Nothing is ranked: ").append(v(rankings, "noTrade")).append(" [get_strategy_rankings]\n");
            return;
        }
        int index = 0;
        if (wanted != null) {
            String w = norm(wanted);
            for (int i = 0; i < ranked.size(); i++) {
                String s = norm(ranked.get(i).path("strategy").asText());
                if (!w.isEmpty() && (s.contains(w) || w.contains(s))) {
                    index = i;
                    break;
                }
            }
        }
        JsonNode row = ranked.get(index);
        e.append("- Ranking [get_strategy_rankings]: ").append(label(row)).append(" is #").append(index + 1).append(" of ").append(ranked.size())
                .append(" [").append(v(row, "versionId")).append("]: score ").append(v(row, "score")).append(", decision ").append(v(row, "decision"))
                .append(", direction ").append(v(row, "direction")).append(", regime ").append(v(row, "regime")).append(", news bias ")
                .append(v(row, "newsBias")).append(", event risk ").append(v(row, "eventRisk")).append(".\n");
        list(e, "Hard blocks", row.path("hardBlocks"));
        list(e, "Cautions", row.path("cautions"));
        for (JsonNode c : row.path("context")) {
            e.append("- Context [get_strategy_rankings]: ").append(c.path("name").asText()).append(" ").append(c.path("status").asText()).append(" ")
                    .append(c.path("value").asText()).append(c.hasNonNull("delta") ? " (" + signed(c.path("delta").asInt()) + ")" : "").append('\n');
        }
        for (int i = 0; i < ranked.size() && i < 4; i++) {
            if (i != index) {
                JsonNode other = ranked.get(i);
                e.append("- #").append(i + 1).append(" [get_strategy_rankings]: ").append(label(other)).append(", score ").append(v(other, "score"))
                        .append(", decision ").append(v(other, "decision")).append('\n');
            }
        }
        JsonNode strategy = call("get_strategy", Map.of("strategy", row.path("strategyId").asText(), "version", row.path("version").asInt()), ctx, onTool, e);
        if (strategy == null) {
            return;
        }
        JsonNode score = strategy.path("score");
        if (score.isMissingNode() || score.isNull()) {
            e.append("- No Hejje Score has been computed for this version yet [get_strategy].\n");
        } else {
            e.append("- Score breakdown [get_strategy]: final ").append(v(score, "finalScore")).append(" from base ").append(v(score, "base"))
                    .append(score.hasNonNull("cap") ? " (" + score.path("cap").asText() + ")" : "").append(" on ").append(v(score, "instrument")).append('\n');
            for (JsonNode c : score.path("components")) {
                e.append("  - component ").append(c.path("name").asText()).append(": score ").append(c.path("score").asText()).append(" × weight ")
                        .append(c.path("weight").asText()).append(" = ").append(c.path("contribution").asText()).append('\n');
            }
            for (JsonNode a : score.path("adjustments")) {
                e.append("  - adjustment ").append(a.path("name").asText()).append(" ").append(signed(a.path("delta").asInt()));
                if (a.path("evidence").size() > 0) {
                    e.append(": ").append(a.path("evidence").get(0).asText());
                }
                e.append('\n');
            }
        }
        e.append("- Rules [get_strategy]: entry ").append(join(strategy.path("entryConditions"))).append("; stop ").append(v(strategy, "stop"))
                .append("; target ").append(v(strategy, "target")).append('\n');
    }

    private void compare(List<String> args, ToolContext ctx, Consumer<ToolResult> onTool, StringBuilder e) {
        if (args.size() < 2) {
            e.append("- Could not tell which two strategies to compare; ask as \"compare A and B\".\n");
            return;
        }
        JsonNode list = call("list_strategies", Map.of(), ctx, onTool, e);
        if (list == null) {
            return;
        }
        Ref a = resolve(args.get(0), list.path("strategies"));
        Ref b = resolve(args.get(1), list.path("strategies"));
        if (a == null || b == null) {
            List<String> known = new ArrayList<>();
            list.path("strategies").forEach(s -> known.add(s.path("slug").asText()));
            e.append("- Could not find a strategy matching '").append(a == null ? args.get(0) : args.get(1)).append("' (known: ").append(String.join(", ", known))
                    .append(") [list_strategies]\n");
            return;
        }
        if (a.strategy().path("strategyId").equals(b.strategy().path("strategyId")) && a.version() != null && b.version() != null) {
            JsonNode cmp = call("compare_strategy_versions", Map.of("strategy", a.strategy().path("strategyId").asText(), "a", a.version(), "b", b.version()), ctx,
                    onTool, e);
            if (cmp != null) {
                row(e, "compare_strategy_versions", cmp.path("a"));
                row(e, "compare_strategy_versions", cmp.path("b"));
                for (JsonNode delta : cmp.path("deltas")) {
                    e.append("  - delta ").append(delta.toString()).append('\n');
                }
                e.append("- Verdict [compare_strategy_versions]: ").append(v(cmp, "verdict")).append('\n');
            }
            return;
        }
        if (a.version() != null || b.version() != null) {
            e.append("- Different strategies are compared on their latest versions [list_strategies].\n");
        }
        JsonNode cmp = call("compare_strategies", Map.of("versionIds", List.of(a.strategy().path("latestVersionId").asText(),
                b.strategy().path("latestVersionId").asText())), ctx, onTool, e);
        if (cmp != null) {
            cmp.path("rows").forEach(r -> row(e, "compare_strategies", r));
        }
    }

    private record Ref(JsonNode strategy, Integer version) {}

    private static Ref resolve(String text, JsonNode strategies) {
        Matcher m = VERSIONED.matcher(text.trim());
        String name = m.matches() ? m.group(1) : text;
        Integer version = m.matches() ? Integer.valueOf(m.group(2)) : null;
        String wanted = norm(name);
        if (wanted.isEmpty()) {
            return null;
        }
        for (JsonNode s : strategies) {
            if (norm(s.path("slug").asText()).equals(wanted) || norm(s.path("name").asText()).equals(wanted)) {
                return new Ref(s, version);
            }
        }
        for (JsonNode s : strategies) {
            if (norm(s.path("slug").asText()).contains(wanted) || norm(s.path("name").asText()).contains(wanted)) {
                return new Ref(s, version);
            }
        }
        return null;
    }

    private void workingToday(ToolContext ctx, Consumer<ToolResult> onTool, StringBuilder e) {
        JsonNode pnl = call("get_pnl_breakdown", Map.of("groupBy", "strategy"), ctx, onTool, e);
        if (pnl != null) {
            e.append("- Closed trades today [get_pnl_breakdown]: ").append(v(pnl, "trades")).append(" trades, net ").append(v(pnl, "netPnl")).append(" rupees\n");
            for (JsonNode b : pnl.path("buckets")) {
                e.append("  - ").append(b.path("label").asText()).append(": ").append(b.path("trades").asText()).append(" trades, ").append(b.path("wins").asText())
                        .append(" wins, net ").append(b.path("netPnl").asText()).append(" rupees").append(b.hasNonNull("averageR") ? ", average " + b.path("averageR").asText() + "R" : "")
                        .append('\n');
            }
        }
        JsonNode rankings = call("get_strategy_rankings", Map.of("limit", 10), ctx, onTool, e);
        if (rankings != null) {
            if (rankings.path("ranked").isEmpty()) {
                e.append("- Nothing is ranked: ").append(v(rankings, "noTrade")).append(" [get_strategy_rankings]\n");
            }
            for (JsonNode r : rankings.path("ranked")) {
                e.append("- Now [get_strategy_rankings]: ").append(label(r)).append(" → ").append(v(r, "decision")).append(", score ").append(v(r, "score"))
                        .append(r.path("hardBlocks").size() > 0 ? ", blocked: " + join(r.path("hardBlocks")) : "").append('\n');
            }
        }
    }

    private void losses(ToolContext ctx, Consumer<ToolResult> onTool, StringBuilder e) {
        JsonNode report = call("get_loss_attribution", Map.of(), ctx, onTool, e);
        JsonNode combo = null;
        if (report != null) {
            JsonNode a = report.path("attribution");
            e.append("- ACTUAL [get_loss_attribution] ").append(v(report, "from")).append(" to ").append(v(report, "to")).append(": ").append(v(a, "trades"))
                    .append(" closed trades, net ").append(v(a, "netPnl")).append(" rupees; ").append(v(a, "losers")).append(" losers lost ").append(v(a, "grossLosses"))
                    .append(" rupees, ").append(v(a, "winners")).append(" winners made ").append(v(a, "grossWins")).append(" rupees.\n");
            if (a.hasNonNull("headline")) {
                e.append("- ACTUAL [get_loss_attribution]: ").append(a.path("headline").asText()).append('\n');
            }
            for (JsonNode d : a.path("dimensions")) {
                String name = d.path("name").asText();
                if (!List.of("family", "trend", "event", "news", "exitReason").contains(name)) {
                    continue;
                }
                List<String> parts = new ArrayList<>();
                for (JsonNode b : d.path("buckets")) {
                    if (parts.size() == 3 || b.path("losses").decimalValue().signum() == 0) {
                        break;
                    }
                    parts.add(b.path("key").asText() + " " + b.path("lossSharePct").asText() + "% of losses (" + b.path("losses").asText() + " rupees, "
                            + b.path("losers").asText() + " of " + b.path("trades").asText() + " trades lost)");
                }
                if (!parts.isEmpty()) {
                    e.append("- ACTUAL losses by ").append(name).append(" [get_loss_attribution]: ").append(String.join("; ", parts)).append('\n');
                }
            }
            JsonNode top = a.path("familyByTrend").path(0);
            if (!top.isMissingNode() && top.path("losses").decimalValue().signum() > 0 && top.path("key").asText().contains(" × ")) {
                combo = top;
            }
        }
        JsonNode slippage = call("get_slippage_stats", Map.of(), ctx, onTool, e);
        if (slippage != null) {
            JsonNode s = slippage.path("slippage");
            e.append("- ACTUAL slippage [get_slippage_stats]: entry mean ").append(v(s.path("entry"), "meanBps")).append(" bps over ").append(v(s.path("entry"), "trades"))
                    .append(" trades, exit mean ").append(v(s.path("exit"), "meanBps")).append(" bps over ").append(v(s.path("exit"), "trades"))
                    .append(" trades, estimated cost ").append(v(s, "totalCostRupees")).append(" rupees\n");
        }
        JsonNode adherence = call("get_rule_adherence", Map.of(), ctx, onTool, e);
        if (adherence != null) {
            JsonNode a = adherence.path("adherence");
            e.append("- ACTUAL rule adherence [get_rule_adherence]: mean ").append(v(a, "meanAdherencePct")).append("% over ").append(v(a, "withAdherence"))
                    .append(" reviewed trades; ").append(v(a, "fullAdherence")).append(" fully adherent, ").append(v(a, "setupInvalid")).append(" with an invalid setup, ")
                    .append(v(a, "manualExits")).append(" manual exits\n");
        }
        if (combo != null) {
            String[] key = combo.path("key").asText().split(" × ", 2);
            JsonNode cf = call("run_counterfactual", Map.of("exclude", Map.of("families", List.of(key[0]), "trends", List.of(key[1]))), ctx, onTool, e);
            if (cf != null) {
                JsonNode c = cf.path("counterfactual");
                e.append("- SIMULATED — a counterfactual, NOT actual results [run_counterfactual]: without the ").append(v(c, "excludedTrades")).append(' ')
                        .append(key[0]).append(" trades during ").append(key[1]).append(" sessions, net would have been ").append(v(c.path("simulated"), "netPnl"))
                        .append(" rupees instead of the actual ").append(v(c.path("actual"), "netPnl")).append(", and max drawdown ")
                        .append(v(c.path("simulated"), "maxDrawdown")).append(" rupees instead of the actual ").append(v(c.path("actual"), "maxDrawdown"))
                        .append(". ").append(v(c, "note")).append('\n');
            }
        }
        e.append("- When answering, keep ACTUAL and SIMULATED figures clearly separate and say that the counterfactual is hypothetical.\n");
    }

    private static void row(StringBuilder e, String tool, JsonNode r) {
        e.append("- ").append(v(r, "slug")).append(" v").append(v(r, "version")).append(" [").append(v(r, "versionId")).append("] [").append(tool)
                .append("]: status ").append(v(r, "status")).append(", trades ").append(v(r, "trades")).append(", win rate ").append(v(r, "winRate"))
                .append(", profit factor ").append(v(r, "profitFactor")).append(", expectancy ").append(v(r, "expectancyR")).append("R, max drawdown ")
                .append(v(r, "maxDrawdownR")).append("R, Hejje Score ").append(v(r, "hejjeScore")).append(", similar regime ")
                .append(v(r, "similarRegimePerformance")).append('\n');
    }

    private static String label(JsonNode row) {
        return v(row, "strategy") + " v" + v(row, "version") + " on " + v(row, "instrument");
    }

    private static void list(StringBuilder e, String name, JsonNode items) {
        if (items.size() > 0) {
            e.append("- ").append(name).append(" [get_strategy_rankings]: ").append(join(items)).append('\n');
        }
    }

    private static String join(JsonNode items) {
        List<String> out = new ArrayList<>();
        items.forEach(i -> out.add(i.asText()));
        return out.isEmpty() ? "none" : String.join("; ", out);
    }

    private static String v(JsonNode node, String field) {
        JsonNode f = node.path(field);
        return f.isMissingNode() || f.isNull() ? "n/a" : f.asText();
    }

    private static String signed(int delta) {
        return delta > 0 ? "+" + delta : String.valueOf(delta);
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
