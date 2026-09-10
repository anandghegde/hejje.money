package money.hejje.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.backtest.Backtest;
import money.hejje.backtest.BacktestMetrics;
import money.hejje.backtest.BacktestService;
import money.hejje.backtest.experiments.ExperimentService;
import money.hejje.backtest.experiments.VariantPreview;
import money.hejje.llm.LlmException;
import money.hejje.llm.LlmService;
import money.hejje.llm.Prompts;
import money.hejje.llm.StructuredOutput;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import org.springframework.stereotype.Service;

/**
 * Strategy experiment agent (PRD 24, 66D role 8; plan M4.7): the LLM (profile {@code research}, prompt
 * {@code experiment_agent_v1}) proposes variants as deltas; Hejje validates every one against the base definition. The
 * backtester — not the model — produces results, through {@link ExperimentService}.
 */
@Service
public class ExperimentAgent {

    public static final String PROMPT = "experiment_agent_v1.txt";
    public static final String PROMPT_VERSION = "experiment_agent_v1";

    public record ProposedVariant(String name, String rationale, Map<String, Object> delta, boolean valid, List<String> errors, List<String> entryConditions,
            int parameterCount, String yaml) {}

    public record Proposals(UUID baseVersionId, String goal, List<ProposedVariant> variants) {}

    static final String SCHEMA = """
            {"type":"object","properties":{"variants":{"type":"array","minItems":1,"maxItems":8,"items":{"type":"object",
             "properties":{"name":{"type":"string","minLength":1},"rationale":{"type":"string"},"delta":{"type":"object"}},"required":["name","delta"]}}},
             "required":["variants"]}""";

    private final LlmService llm;
    private final StructuredOutput structured;
    private final ExperimentService experiments;
    private final StrategyService strategies;
    private final BacktestService backtests;
    private final ObjectMapper json;

    ExperimentAgent(LlmService llm, StructuredOutput structured, ExperimentService experiments, StrategyService strategies, BacktestService backtests,
            ObjectMapper json) {
        this.llm = llm;
        this.structured = structured;
        this.experiments = experiments;
        this.strategies = strategies;
        this.backtests = backtests;
        this.json = json;
    }

    @SuppressWarnings("unchecked")
    public Proposals propose(UUID baseVersionId, String goal) {
        if (!llm.enabled()) {
            throw new LlmException.Unavailable("Proposing variants needs the LLM (HEJJE_LLM_ENABLED=false); run_experiment works without it");
        }
        StrategyVersion base = strategies.versionById(baseVersionId).orElseThrow(() -> new ToolException(ToolStatus.NOT_FOUND, "Unknown version " + baseVersionId));
        String system = Prompts.fill(Prompts.load(PROMPT), Map.of("goal", goal == null || goal.isBlank() ? "Improve the strategy's out-of-sample results" : goal.strip(),
                "yaml", base.definitionYaml().strip(), "summary", summary(baseVersionId), "dsl", Prompts.load("strategy-dsl.md")));
        JsonNode answer;
        try {
            answer = structured.ask("research", "experiment-agent", PROMPT_VERSION, system, "Propose the variants now.", json.readTree(SCHEMA));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        List<ProposedVariant> out = new ArrayList<>();
        for (JsonNode v : answer.path("variants")) {
            Map<String, Object> delta = json.convertValue(v.path("delta"), Map.class);
            VariantPreview p = experiments.preview(baseVersionId, delta);
            out.add(new ProposedVariant(v.path("name").asText(), v.path("rationale").asText(null), delta, p.valid(), p.errors(), p.entryConditions(), p.parameterCount(),
                    p.yaml()));
        }
        return new Proposals(baseVersionId, goal, out);
    }

    private String summary(UUID versionId) {
        return backtests.baseBacktest(versionId).map(ExperimentAgent::describe).orElse("No backtest yet.");
    }

    private static String describe(Backtest b) {
        StringBuilder s = new StringBuilder("Backtest " + b.id() + " " + b.spec().from() + " to " + b.spec().to() + ":\n");
        line(s, "overall", b.metrics());
        if (b.bySplit() != null) {
            b.bySplit().forEach((split, m) -> line(s, split.name(), m));
        }
        if (b.warnings() != null) {
            b.warnings().forEach(w -> s.append("- warning ").append(w.code()).append(": ").append(w.message()).append('\n'));
        }
        return s.toString();
    }

    private static void line(StringBuilder s, String name, BacktestMetrics m) {
        if (m != null) {
            s.append("- ").append(name).append(": ").append(m.totalTrades()).append(" trades, expectancy ").append(String.format("%.2f", m.expectancyR()))
                    .append("R, profit factor ").append(m.profitFactor() == null ? "n/a" : String.format("%.2f", m.profitFactor())).append(", max drawdown ")
                    .append(String.format("%.1f", m.maxDrawdownR())).append("R\n");
        }
    }
}
