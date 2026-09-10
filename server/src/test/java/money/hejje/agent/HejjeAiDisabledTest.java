package money.hejje.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;
import money.hejje.agent.internal.AnalystFlows;
import money.hejje.agent.internal.ConversationStore;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import money.hejje.llm.LlmException;
import money.hejje.llm.LlmService;
import money.hejje.signals.SignalService;
import org.junit.jupiter.api.Test;

/** With the LLM off, Hejje AI reports why and refuses before touching tools or storage (the platform itself is unaffected). */
class HejjeAiDisabledTest {

    @Test
    void disabledLlmMeansNoChat() {
        LlmService llm = mock(LlmService.class);
        when(llm.enabled()).thenReturn(false);
        AgentToolService tools = mock(AgentToolService.class);
        ConversationStore store = mock(ConversationStore.class);
        HejjeAiService ai = new HejjeAiService(llm, tools, mock(AnalystFlows.class), store, new AgentProperties(null, null), mock(SignalService.class),
                new HejjeClock(MutableClock.atIst("2026-09-10T10:00:00"), MutableClock.IST, (d, e) -> false));
        assertThat(ai.status().enabled()).isFalse();
        assertThat(ai.status().reason()).contains("HEJJE_LLM_ENABLED=false");
        HejjePrincipal user = new HejjePrincipal(UUID.randomUUID(), "admin", HejjePrincipal.Type.USER, Set.of("market:read"));
        assertThatThrownBy(() -> ai.ask(user, null, "What is working today?", null, e -> { })).isInstanceOf(LlmException.Unavailable.class);
        verifyNoInteractions(tools, store);
    }
}
