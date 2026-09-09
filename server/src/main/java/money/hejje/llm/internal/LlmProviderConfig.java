package money.hejje.llm.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import money.hejje.llm.FixtureLlmProvider;
import money.hejje.llm.LlmProperties;
import money.hejje.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds one provider bean per {@code hejje.llm.providers} entry. */
@Configuration
class LlmProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderConfig.class);

    @Bean
    List<LlmProvider> llmProviders(LlmProperties props, ObjectMapper json) {
        List<LlmProvider> out = new ArrayList<>();
        for (Map.Entry<String, LlmProperties.Provider> e : props.providers().entrySet()) {
            switch (e.getValue().type()) {
                case "openai-compatible" -> out.add(new OpenAiCompatibleProvider(e.getKey(), e.getValue(), json));
                case "fixture" -> out.add(new FixtureLlmProvider(e.getKey()));
                default -> log.warn("Unknown LLM provider type '{}' for '{}'; skipped", e.getValue().type(), e.getKey());
            }
        }
        return out;
    }
}
