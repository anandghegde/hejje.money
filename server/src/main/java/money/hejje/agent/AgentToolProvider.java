package money.hejje.agent;

import java.util.List;

/** A bean contributing tools to the {@link ToolRegistry}. */
public interface AgentToolProvider {
    List<AgentTool> tools();
}
