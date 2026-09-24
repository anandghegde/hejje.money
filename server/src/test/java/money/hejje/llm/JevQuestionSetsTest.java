package money.hejje.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/** Question set files, question validation and the API shapes they produce (plan M9.1). */
class JevQuestionSetsTest {

    final JevQuestionSets sets = new JevQuestionSets(new DefaultResourceLoader());

    @Test
    void loadsASetFromItsFileInTheApiShape() {
        JevQuestionSet set = sets.get("sample");
        assertThat(set.version()).isEqualTo("1");
        assertThat(set.questions()).containsOnlyKeys("urgent", "team", "mood");

        ObjectNode urgent = set.questions().get("urgent").toJson();
        assertThat(urgent.path("type").asText()).isEqualTo("noul");
        assertThat(urgent.path("criteria").path("true").asText()).isEqualTo("Explicitly time-sensitive");

        ObjectNode team = set.questions().get("team").toJson();
        assertThat(team.path("criteria").fieldNames()).toIterable().containsExactly("billing", "technical", "sales");
        assertThat(team.path("criteria").get("sales").isNull()).isTrue();

        ObjectNode mood = set.questions().get("mood").toJson();
        assertThat(mood.path("instructions").path("question").asText()).contains("frustrated");
        assertThat(mood.path("criteria")).hasSize(3);
        assertThat(sets.get("sample")).isSameAs(set);
    }

    @Test
    void anUnknownSetIsEmptyAndABadFileSaysWhy() {
        assertThat(sets.find("nope")).isEmpty();
        assertThat(sets.find("../etc")).isEmpty();
        assertThatThrownBy(() -> sets.find("bad-score")).hasMessageContaining("mood").hasMessageContaining("2 to 10 levels");
        assertThatThrownBy(() -> sets.find("wrong-name")).hasMessageContaining("declares name 'something-else'");
    }

    @Test
    void questionsAreValidated() {
        assertThatThrownBy(() -> JevQuestion.noul(" ")).hasMessageContaining("instructions");
        assertThatThrownBy(() -> JevQuestion.choice("Pick", Map.of("only", "one"))).hasMessageContaining("2 to 255 options");
        assertThatThrownBy(() -> JevQuestion.score("Rate", List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k")))
                .hasMessageContaining("2 to 10 levels");
        assertThatThrownBy(() -> new JevQuestionSet("Bad Name", "1", Map.of("a", JevQuestion.noul("a?")))).hasMessageContaining("lower-case");
        assertThatThrownBy(() -> new JevQuestionSet("x", "1", Map.of())).hasMessageContaining("no questions");
    }

    @Test
    void answersExposeTheirProbability() {
        ObjectMapper json = new ObjectMapper();
        JsonNode choice = json.createObjectNode().put("type", "choice").put("choice", "b").put("confidence", 0.5)
                .set("probabilities", json.createObjectNode().put("a", 0.3).put("b", 0.7));
        JevAnswer a = JevAnswer.fromJson("k", choice);
        assertThat(a.probability()).isEqualTo(0.7);
        assertThat(a.probabilityOf("a")).isEqualTo(0.3);
        assertThat(a.probabilityOf("z")).isZero();
        JevAnswer inferred = JevAnswer.fromJson("n", json.createObjectNode().put("noul", 0.2));
        assertThat(inferred.type()).isEqualTo("noul");
        assertThat(inferred.probability()).isEqualTo(0.2);
    }
}
