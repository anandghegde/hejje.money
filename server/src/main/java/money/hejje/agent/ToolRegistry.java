package money.hejje.agent;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import money.hejje.agent.internal.ToolSchemas;
import org.springframework.stereotype.Component;

/** Every registered tool by name (sorted), with its catalog entry. Duplicate names fail startup. */
@Component
public class ToolRegistry {

    public record Registered(AgentTool tool, ToolDescriptor descriptor) {}

    private final Map<String, Registered> tools = new TreeMap<>();

    public ToolRegistry(List<AgentToolProvider> providers) {
        for (AgentToolProvider provider : providers) {
            for (AgentTool tool : provider.tools()) {
                if (tools.containsKey(tool.name())) {
                    throw new IllegalStateException("Duplicate agent tool " + tool.name());
                }
                ToolDescriptor d = new ToolDescriptor(tool.name(), tool.description(), tool.requiredScope(), tool.transactional(), tool.inputSchema(),
                        ToolSchemas.of(tool.outputType()));
                tools.put(tool.name(), new Registered(tool, d));
            }
        }
    }

    public Optional<Registered> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolDescriptor> catalog() {
        return tools.values().stream().map(Registered::descriptor).toList();
    }
}
