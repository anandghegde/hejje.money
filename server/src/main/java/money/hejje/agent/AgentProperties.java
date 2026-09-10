package money.hejje.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent layer settings ({@code hejje.agent.*}).
 *
 * @param ai Hejje AI chat: master switch (also needs {@code hejje.llm.enabled}), LLM profile for the first question of a
 *           conversation and for follow-ups, the tool-loop step limit, how much of each tool result the model sees, and
 *           how many earlier turns are replayed
 */
@ConfigurationProperties("hejje.agent")
public record AgentProperties(@DefaultValue Ai ai) {

    public AgentProperties {
        ai = ai == null ? new Ai(true, "reasoning", "fast", 8, 12_000, 10) : ai;
    }

    public record Ai(@DefaultValue("true") boolean enabled, @DefaultValue("reasoning") String profile, @DefaultValue("fast") String followUpProfile,
            @DefaultValue("8") int maxSteps, @DefaultValue("12000") int maxToolResultChars, @DefaultValue("10") int historyTurns) {
    }
}
