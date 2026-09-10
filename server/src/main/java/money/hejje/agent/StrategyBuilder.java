package money.hejje.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.llm.LlmException;
import money.hejje.llm.LlmMessage;
import money.hejje.llm.LlmRequest;
import money.hejje.llm.LlmResponse;
import money.hejje.llm.LlmService;
import money.hejje.llm.Prompts;
import money.hejje.strategy.RuleWords;
import money.hejje.strategy.Strategy;
import money.hejje.strategy.StrategyException;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import money.hejje.strategy.ValidationReport;
import org.springframework.stereotype.Service;

/**
 * Natural-language strategy builder (PRD 22, 66D role 7; plan M4.6): the LLM (profile {@code reasoning}, temperature 0,
 * prompt {@code strategy_builder_v1} with the DSL reference and two bundled examples) writes YAML; Hejje validates it
 * (schema + semantics) and feeds the errors back for up to three fixes; a valid result is saved as a DRAFT version with
 * change note "NL draft" — under a new strategy, or as the next version of an existing one. Nothing here can change a
 * strategy's status: the M2.1 lifecycle (backtest, validation, human status changes) still stands between a draft and LIVE.
 */
@Service
public class StrategyBuilder {

    public static final String PROMPT = "strategy_builder_v1.txt";
    public static final String PROMPT_VERSION = "strategy_builder_v1";
    public static final String CHANGE_NOTE = "NL draft";
    static final int MAX_FIXES = 3;
    private static final Pattern FENCED = Pattern.compile("```(?:yaml|yml)?\\s*\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern NAME_LINE = Pattern.compile("(?m)^name:.*$");

    private final LlmService llm;
    private final StrategyService strategies;

    StrategyBuilder(LlmService llm, StrategyService strategies) {
        this.llm = llm;
        this.strategies = strategies;
    }

    /** @param strategyRef optional id or slug: the draft becomes that strategy's next version */
    public StrategyDraft draft(String description, String strategyRef, HejjePrincipal principal) {
        if (description == null || description.isBlank() || description.length() > 2_000) {
            throw new IllegalArgumentException("description is required (at most 2000 characters)");
        }
        if (!llm.enabled()) {
            throw new LlmException.Unavailable("The strategy builder needs the LLM (HEJJE_LLM_ENABLED=false)");
        }
        Strategy parent = strategyRef == null || strategyRef.isBlank() ? null : resolve(strategyRef.trim());
        StrategyVersion parentVersion = parent == null ? null
                : strategies.latestVersion(parent.id()).orElseThrow(() -> new StrategyException.Conflict("Strategy " + parent.slug() + " has no versions"));

        String system = Prompts.fill(Prompts.load(PROMPT), Map.of("dsl", Prompts.load("strategy-dsl.md"), "example1", resource("strategies/nifty_orb.yaml"),
                "example2", resource("strategies/vwap_reversion.yaml")));
        String ask = "Description:\n" + description.strip() + "\n\n" + (parent == null ? "Create a new strategy."
                : "Write the next version of this existing strategy; keep the name " + parent.slug() + ":\n```yaml\n" + parentVersion.definitionYaml() + "\n```");
        List<LlmMessage> messages = new ArrayList<>(List.of(LlmMessage.user(ask)));
        List<StrategyDraft.Attempt> attempts = new ArrayList<>();
        String yaml = null;
        ValidationReport report = null;
        for (int i = 0; i <= MAX_FIXES; i++) {
            LlmResponse answer = llm.complete(new LlmRequest("reasoning", "strategy-builder", PROMPT_VERSION, system, null, null, 0.0, false, messages, List.of()));
            yaml = extractYaml(answer.text());
            if (parent != null) {
                yaml = withName(yaml, parent.slug());
            }
            report = strategies.validate(yaml);
            List<String> errors = report.errors().stream().map(e -> e.path() + ": " + e.message()).toList();
            attempts.add(new StrategyDraft.Attempt(i + 1, yaml, errors));
            if (report.valid()) {
                break;
            }
            messages.add(LlmMessage.assistant(answer.text(), List.of()));
            messages.add(LlmMessage.user("Hejje rejected that definition:\n- " + String.join("\n- ", errors)
                    + "\nFix every error and reply with the complete corrected YAML only, in a ```yaml block."));
        }
        if (!report.valid()) {
            return new StrategyDraft(false, parent == null ? null : parent.id(), parent == null ? null : parent.slug(), null, null, null, null, yaml, List.of(),
                    parentVersion == null ? null : parentVersion.definitionYaml(), parentVersion == null ? null : parentVersion.version(), attempts,
                    attempts.get(attempts.size() - 1).errors());
        }
        StrategyVersion created;
        try {
            if (parent != null) {
                created = strategies.addVersion(parent.id(), yaml, CHANGE_NOTE, principal.name());
            } else {
                yaml = withUniqueName(yaml, report.definition().name());
                created = strategies.create(yaml, CHANGE_NOTE, principal.name());
            }
        } catch (StrategyException.Conflict e) {
            return new StrategyDraft(false, parent == null ? null : parent.id(), parent == null ? null : parent.slug(), null, null, null, null, yaml, List.of(),
                    parentVersion == null ? null : parentVersion.definitionYaml(), parentVersion == null ? null : parentVersion.version(), attempts,
                    List.of(e.getMessage()));
        }
        Strategy strategy = strategies.find(created.strategyId()).orElseThrow();
        return new StrategyDraft(true, strategy.id(), strategy.slug(), created.id(), created.version(), created.status().name(), created.changeNote(),
                created.definitionYaml(), RuleWords.describe(created.definition()), parentVersion == null ? null : parentVersion.definitionYaml(),
                parentVersion == null ? null : parentVersion.version(), attempts, List.of());
    }

    static String extractYaml(String text) {
        Matcher m = FENCED.matcher(text == null ? "" : text);
        return (m.find() ? m.group(1) : text == null ? "" : text).strip() + "\n";
    }

    static String withName(String yaml, String name) {
        return NAME_LINE.matcher(yaml).find() ? NAME_LINE.matcher(yaml).replaceFirst("name: " + name) : "name: " + name + "\n" + yaml;
    }

    /** A new strategy keeps the model's name unless it is taken: then {@code _nl}, {@code _nl2}, ... is appended. */
    private String withUniqueName(String yaml, String name) {
        if (strategies.findBySlug(name).isEmpty()) {
            return yaml;
        }
        for (int i = 1; i < 100; i++) {
            String candidate = (name.length() > 58 ? name.substring(0, 58) : name) + (i == 1 ? "_nl" : "_nl" + i);
            if (strategies.findBySlug(candidate).isEmpty()) {
                return withName(yaml, candidate);
            }
        }
        throw new StrategyException.Conflict("No free name for " + name);
    }

    private Strategy resolve(String ref) {
        try {
            return strategies.find(UUID.fromString(ref)).orElseThrow(() -> new StrategyException.NotFound("Strategy " + ref + " not found"));
        } catch (IllegalArgumentException notAnId) {
            return strategies.findBySlug(ref).orElseThrow(() -> new StrategyException.NotFound("Strategy " + ref + " not found"));
        }
    }

    private static String resource(String path) {
        try (InputStream in = StrategyBuilder.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            return "";
        }
    }

}
