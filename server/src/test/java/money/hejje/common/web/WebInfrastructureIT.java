package money.hejje.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import money.hejje.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class WebInfrastructureIT extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    MockMvc mvc;

    @Test
    void echoesProvidedCorrelationId() {
        String id = UUID.randomUUID().toString();
        HttpHeaders headers = new HttpHeaders();
        headers.set(CorrelationIdFilter.HEADER, id);
        ResponseEntity<String> response = rest.exchange("/api/v1/server/health", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getHeaders().getFirst(CorrelationIdFilter.HEADER)).isEqualTo(id);
    }

    @Test
    void generatesCorrelationIdWhenMissingOrInvalid() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/server/health", String.class);
        String generated = response.getHeaders().getFirst(CorrelationIdFilter.HEADER);
        assertThat(generated).isNotBlank();
        assertThat(UUID.fromString(generated).version()).isEqualTo(7);

        HttpHeaders headers = new HttpHeaders();
        headers.set(CorrelationIdFilter.HEADER, "not-a-uuid");
        ResponseEntity<String> replaced = rest.exchange("/api/v1/server/health", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(replaced.getHeaders().getFirst(CorrelationIdFilter.HEADER)).isNotEqualTo("not-a-uuid");
    }

    @Test
    void validationErrorsAreProblemJsonPerField() throws Exception {
        mvc.perform(post("/api/v1/test/validation").with(user("tester"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().exists(CorrelationIdFilter.HEADER))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[?(@.field=='name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='quantity')]").exists());
    }

    @Test
    void illegalArgumentIsBadRequestProblem() throws Exception {
        mvc.perform(post("/api/v1/test/validation").with(user("tester"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"boom\",\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("boom is not allowed"));
    }
}
