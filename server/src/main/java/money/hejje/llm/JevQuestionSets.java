package money.hejje.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/** Question sets by name ({@code classpath:jev/<name>.yaml}, bundled from the repo's {@code config/jev/}). */
@Component
public class JevQuestionSets {

    private final ResourceLoader resources;
    private final Map<String, JevQuestionSet> cache = new ConcurrentHashMap<>();

    JevQuestionSets(ResourceLoader resources) {
        this.resources = resources;
    }

    /** The set called {@code name}, or empty when there is no such file. A malformed file throws. */
    public Optional<JevQuestionSet> find(String name) {
        if (name == null || !name.matches("[a-z0-9_-]+")) {
            return Optional.empty();
        }
        JevQuestionSet cached = cache.get(name);
        if (cached != null) {
            return Optional.of(cached);
        }
        Resource resource = resources.getResource("classpath:jev/" + name + ".yaml");
        if (!resource.exists()) {
            return Optional.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            JevQuestionSet set = JevQuestionSet.fromJson(new ObjectMapper(new YAMLFactory()).readTree(in));
            if (!set.name().equals(name)) {
                throw new IllegalStateException("config/jev/" + name + ".yaml declares name '" + set.name() + "'");
            }
            cache.put(name, set);
            return Optional.of(set);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Question set " + name + " is unreadable: " + e.getMessage(), e);
        }
    }

    public JevQuestionSet get(String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException("Unknown Jev question set " + name));
    }
}
