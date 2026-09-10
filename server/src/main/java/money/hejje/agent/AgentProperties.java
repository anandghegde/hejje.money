package money.hejje.agent;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent layer settings ({@code hejje.agent.*}).
 *
 * @param ai Hejje AI chat: master switch (also needs {@code hejje.llm.enabled}), LLM profile for the first question of a
 *           conversation and for follow-ups, the tool-loop step limit, how much of each tool result the model sees, and
 *           how many earlier turns are replayed
 * @param approvals agent proposals (M4.4): how long an approval stays open (signal proposals use the signal's validity),
 *                  the autonomy level for proposals not tied to a deployed strategy, and how often expired ones are swept
 */
@ConfigurationProperties("hejje.agent")
public record AgentProperties(@DefaultValue Ai ai, @DefaultValue Approvals approvals) {

    public AgentProperties {
        ai = ai == null ? new Ai(true, "reasoning", "fast", 8, 12_000, 10) : ai;
        approvals = approvals == null ? new Approvals(Duration.ofMinutes(5), 3, Duration.ofSeconds(30)) : approvals;
    }

    public record Approvals(@DefaultValue("5m") Duration ttl, @DefaultValue("3") int accountAutonomyLevel, @DefaultValue("30s") Duration expirySweep) {
    }

    public record Ai(@DefaultValue("true") boolean enabled, @DefaultValue("reasoning") String profile, @DefaultValue("fast") String followUpProfile,
            @DefaultValue("8") int maxSteps, @DefaultValue("12000") int maxToolResultChars, @DefaultValue("10") int historyTurns) {
    }
}
