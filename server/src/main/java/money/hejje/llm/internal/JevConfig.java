package money.hejje.llm.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import money.hejje.llm.FixtureJev;
import money.hejje.llm.JevProperties;
import money.hejje.llm.JevTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The Jev transport: {@link FixtureJev} for {@code base-url: fixture}, else the HTTP client. */
@Configuration
class JevConfig {

    @Bean
    JevTransport jevTransport(JevProperties props, ObjectMapper json) {
        return props.fixture() ? new FixtureJev() : new JevHttpTransport(props, json);
    }
}
