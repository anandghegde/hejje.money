/**
 * Agent layer (PRD sections 28-30, 48.3, plan M4.2+): every agent capability is an {@link money.hejje.agent.AgentTool}
 * with an input schema, a generated output schema and a required scope, registered in the
 * {@link money.hejje.agent.ToolRegistry}. {@link money.hejje.agent.AgentToolService} enforces the scope from the caller's
 * credential (never from prompt content), validates input and output, and records every call in {@code agent_action}
 * plus an {@code AGENT_TOOL_CALLED} audit event. Tools call internal services, never broker methods.
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.agent;
