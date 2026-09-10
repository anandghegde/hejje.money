package money.hejje.agent.internal.tools;

import static money.hejje.agent.internal.tools.ToolSupport.schema;

import java.util.List;
import money.hejje.agent.AgentTool;
import money.hejje.agent.AgentToolProvider;
import money.hejje.agent.StrategyBuilder;
import money.hejje.agent.StrategyDraft;
import money.hejje.agent.ToolContext;
import money.hejje.agent.ToolException;
import money.hejje.common.security.ScopeCatalog;
import org.springframework.stereotype.Component;

/** Natural-language strategy drafts (scope {@code strategies:write}, plan M4.6). A draft is only ever DRAFT. */
@Component
public class DraftTools implements AgentToolProvider {

    public record DraftInput(String description, String strategy) {}

    private final StrategyBuilder builder;

    DraftTools(StrategyBuilder builder) {
        this.builder = builder;
    }

    @Override
    public List<AgentTool> tools() {
        return List.of(AgentTool.transactional("create_strategy_draft", "Turns a plain-language strategy description into a validated DRAFT strategy "
                        + "definition (YAML plus the rules in words), as a new strategy or, with `strategy`, as that strategy's next version. Hejje validates it "
                        + "and lets the model fix errors up to three times. A draft cannot trade: it still needs a backtest, validation and human status changes.",
                ScopeCatalog.STRATEGIES_WRITE, schema("""
                        {"type":"object","properties":{"description":{"type":"string","minLength":10,"maxLength":2000},
                         "strategy":{"type":"string","description":"Id or slug of an existing strategy to draft a new version of"}},
                         "required":["description"],"additionalProperties":false}"""),
                DraftInput.class, StrategyDraft.class, this::draft));
    }

    StrategyDraft draft(DraftInput in, ToolContext ctx) {
        StrategyDraft draft = builder.draft(in.description(), in.strategy(), ctx.principal());
        if (!draft.created()) {
            throw ToolException.failed("No valid definition after " + draft.attempts().size() + " attempt(s): " + String.join("; ", draft.errors()));
        }
        return draft;
    }
}
