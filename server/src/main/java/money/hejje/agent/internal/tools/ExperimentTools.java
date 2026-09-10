package money.hejje.agent.internal.tools;

import static money.hejje.agent.internal.tools.ToolSupport.schema;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.agent.AgentTool;
import money.hejje.agent.AgentToolProvider;
import money.hejje.agent.ExperimentAgent;
import money.hejje.agent.ToolContext;
import money.hejje.agent.ToolException;
import money.hejje.backtest.Splits;
import money.hejje.backtest.experiments.Experiment;
import money.hejje.backtest.experiments.ExperimentService;
import money.hejje.backtest.experiments.VariantSpec;
import money.hejje.common.security.ScopeCatalog;
import money.hejje.strategy.Strategy;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import org.springframework.stereotype.Component;

/** Strategy experiment tools (plan M4.7): propose (LLM, validated), run (the backtester), inspect. Nothing here changes a strategy's status. */
@Component
public class ExperimentTools implements AgentToolProvider {

    public record ProposeInput(String version, String strategy, String goal) {}

    public record VariantInput(String name, String rationale, Map<String, Object> delta) {}

    public record RunInput(String version, String strategy, String goal, List<VariantInput> variants, String from, String to, String splits) {}

    public record ExperimentInput(String experimentId) {}

    static final String BASE_PROPS = """
            "version":{"type":"string","format":"uuid","description":"Base version id"},
            "strategy":{"type":"string","description":"Strategy id or slug (its latest version is the base)"}""";

    private final ExperimentAgent agent;
    private final ExperimentService experiments;
    private final StrategyService strategies;

    ExperimentTools(ExperimentAgent agent, ExperimentService experiments, StrategyService strategies) {
        this.agent = agent;
        this.experiments = experiments;
        this.strategies = strategies;
    }

    @Override
    public List<AgentTool> tools() {
        return List.of(
                AgentTool.of("propose_variants", "Proposes 3 to 6 variants of a strategy version as deltas (filters or parameter changes) aimed at a goal, "
                        + "preferring out-of-sample robustness and simplicity; Hejje validates every delta. Nothing is run or saved.", ScopeCatalog.STRATEGIES_READ,
                        schema("{\"type\":\"object\",\"properties\":{" + BASE_PROPS + ",\"goal\":{\"type\":\"string\",\"maxLength\":500}},\"additionalProperties\":false}"),
                        ProposeInput.class, ExperimentAgent.Proposals.class, (in, ctx) -> agent.propose(base(in.version(), in.strategy()).id(), in.goal())),
                AgentTool.transactional("run_experiment", "Backtests the base version and each variant (delta) on the same data and split, then ranks them "
                        + "deterministically with overfitting warnings. Runs in the background; poll get_experiment. Promoting a variant is a human action.",
                        ScopeCatalog.STRATEGIES_WRITE, schema("""
                                {"type":"object","properties":{%s,"goal":{"type":"string","maxLength":500},
                                 "variants":{"type":"array","minItems":1,"maxItems":12,"items":{"type":"object","properties":{"name":{"type":"string","minLength":1,"maxLength":64},
                                  "rationale":{"type":"string","maxLength":500},"delta":{"type":"object"}},"required":["name","delta"],"additionalProperties":false}},
                                 "from":{"type":"string","format":"date"},"to":{"type":"string","format":"date"},
                                 "splits":{"type":"string","enum":["FIXED","WALK_FORWARD","NONE"]}},"required":["variants"],"additionalProperties":false}"""
                                .formatted(BASE_PROPS)),
                        RunInput.class, Experiment.class, this::run),
                AgentTool.of("get_experiment", "An experiment with its variants: status, rank, score, verdict (RECOMMENDED, BETTER_OUT_OF_SAMPLE, "
                        + "BETTER_BUT_FRAGILE, NOT_BETTER, BASELINE), metrics per split and overfitting warnings.", ScopeCatalog.STRATEGIES_READ, schema("""
                                {"type":"object","properties":{"experimentId":{"type":"string","format":"uuid"}},"required":["experimentId"],"additionalProperties":false}"""),
                        ExperimentInput.class, Experiment.class,
                        (in, ctx) -> experiments.get(UUID.fromString(in.experimentId())).orElseThrow(() -> ToolException.notFound("Unknown experiment " + in.experimentId()))));
    }

    Experiment run(RunInput in, ToolContext ctx) {
        StrategyVersion base = base(in.version(), in.strategy());
        Splits splits = in.splits() == null ? null : switch (in.splits()) {
            case "WALK_FORWARD" -> Splits.walkForward(6, 2, false);
            case "NONE" -> Splits.NONE;
            default -> Splits.DEFAULT_FIXED;
        };
        List<VariantSpec> variants = in.variants().stream().map(v -> new VariantSpec(v.name(), v.rationale(), v.delta())).toList();
        return experiments.start(base.id(), in.goal(), variants, new ExperimentService.DatasetRequest(ToolSupport.date(in.from(), null), ToolSupport.date(in.to(), null),
                null, splits), ctx.principal().name(), ctx.sessionId());
    }

    private StrategyVersion base(String version, String strategy) {
        if (version != null) {
            return strategies.versionById(UUID.fromString(version)).orElseThrow(() -> ToolException.notFound("Unknown version " + version));
        }
        if (strategy == null) {
            throw ToolException.invalid("Give version or strategy");
        }
        Strategy s;
        try {
            s = strategies.find(UUID.fromString(strategy)).orElseThrow(() -> ToolException.notFound("Unknown strategy " + strategy));
        } catch (IllegalArgumentException notAnId) {
            s = strategies.findBySlug(strategy).orElseThrow(() -> ToolException.notFound("Unknown strategy " + strategy));
        }
        Strategy found = s;
        return strategies.latestVersion(found.id()).orElseThrow(() -> ToolException.notFound("Strategy " + found.slug() + " has no versions"));
    }
}
