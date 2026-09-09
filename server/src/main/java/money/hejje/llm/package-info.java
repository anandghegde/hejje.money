/**
 * LLM provider abstraction (PRD section 66C, plan M3.4 minimal / M4.1 complete): provider-agnostic
 * {@link money.hejje.llm.LlmProvider} with an OpenAI-compatible adapter and a fixture provider for tests, named
 * profiles ({@code fast, reasoning, news, research}) routed to providers, every call logged to {@code llm_call} with
 * tokens/cost/latency and the correlation id, and {@link money.hejje.llm.StructuredOutput} for schema-validated JSON
 * answers. Off by default ({@code hejje.llm.enabled=false}); nothing in the trading core depends on it (PRD 66E).
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.llm;
