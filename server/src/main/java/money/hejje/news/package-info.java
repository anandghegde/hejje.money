/**
 * News as bounded context (PRD section 17, plan M3.4): RSS/Atom/JSON sources polled and deduplicated, deterministic
 * instrument matching through config/aliases.yaml, LLM classification (profile {@code news}, versioned prompt) into
 * retained assessments, and a deterministic bias aggregate per instrument with evidence. Off by default
 * ({@code hejje.news.enabled=false}); without the LLM items are stored but not assessed and the bias is NEUTRAL /
 * unavailable. Rules: docs/news.md.
 */
@org.springframework.modulith.ApplicationModule
package money.hejje.news;
