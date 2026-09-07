# Phase 4 — Agent Layer

Goal of the phase (PRD §70, §28–29, §66D): an LLM-provider-agnostic assistant that operates the platform only through typed, scoped tools; orders are prepared by agents and confirmed by humans (Automation Level 3 maximum). Everything in this phase is optional at runtime.

Order: M4.1 → M4.2 → M4.3 → M4.4 → M4.5 → M4.6 → M4.7. M4.5–M4.7 are independent of each other after M4.4.

---

## M4.1 LLM provider module (complete)  (size: M)

**Tasks.**
1. Finish `llm`: `OpenAiCompatibleProvider` with chat completions, tool/function calling, streaming; `GeminiProvider` native; provider registry from config (`hejje.llm.providers.*`, secrets via `api_key_env` only); profiles `FAST, REASONING, NEWS, RESEARCH` mapping to provider+model; per-profile max tokens, temperature 0 default for structured tasks.
2. Resilience: timeouts, retry with jitter on 429/5xx, circuit breaker per provider, fallback profile optional. Cost/tokens logged per call (`llm_call`), daily budget cap `hejje.llm.daily-cost-cap` → disable with alert.
3. `GET /agents/llm/status` (providers, health, today's usage), `POST /agents/llm/test` (admin; sends a trivial prompt).
4. Contract tests with recorded HTTP fixtures (WireMock) for both providers including a tool-call round trip and a streamed response.

**Verification.** `./gradlew test --tests 'money.hejje.llm.*'`

---

## M4.2 Typed agent tool surface and agent credentials  (size: L)

**Goal.** Every agent capability is a registered tool with a schema, a scope, and an audit trail; tools call internal services, never raw broker methods.

**Tasks.**
1. `agent` module: `AgentTool{name, description, inputSchema, outputSchema, requiredScope, handler}`; `ToolRegistry`; JSON-schema validation of inputs and outputs. Tools per PRD §28, read-only first: `get_market_snapshot, get_market_regime, get_pulse, get_news_context, get_event_calendar, list_strategies, get_strategy, get_strategy_rankings (returns Recommendation objects §29), get_strategy_backtest, compare_strategies, compare_strategy_versions, get_strategy_signal, get_positions, get_orders, get_trades, get_account_risk, calculate_position_size, get_audit_trail`. Outputs are compact DTOs (no raw rows), with ids the agent can cite.
2. Agent sessions: `agent_session(id, client_credential_id, profile, purpose, started_at, ended_at)`, `agent_action(id, session_id, tool, input jsonb, output_summary jsonb, scope_ok, status, latency_ms, correlation_id, ts)`; every call audited as `AGENT_TOOL_CALLED`. Scope enforced at the tool boundary from the credential, independent of any prompt content.
3. Agent credential presets (`POST /auth/clients` helpers): `research` (`market:read strategies:read`), `execution` (`+ orders:prepare`). No preset includes `orders:execute`, `positions:close`, `risk:write`, or `admin`.
4. `GET /agents/tools` (catalog with schemas), `POST /agents/tools/{name}` (direct invocation for external agents, uses caller's credential). Optional task: expose the same registry as an MCP server endpoint (`/mcp`, streamable HTTP) so external agents such as Claude Code can use Hejje tools with a scoped key.
5. Docs `docs/agent-tools.md` generated from the registry.

**Acceptance.** Schema validation rejects malformed input; a `research` credential calling `prepare_order` (added in M4.4) gets `FORBIDDEN` and an audit row; catalog lists every tool with scope.

**Verification.** `./gradlew test --tests 'money.hejje.agent.*'`

---

## M4.3 Hejje AI chat (web + TUI)  (size: L)

**Goal.** Natural-language interface and analyst (PRD §56, §66D roles 1–3) grounded in tool outputs.

**Tasks.**
1. Orchestrator: system prompt (`resources/prompts/hejje_ai_v1.txt`) stating role, non-responsibilities (PRD §66E), tool-use rules, citation rule ("every number must come from a tool result; cite ids"); loop: LLM (profile `REASONING`, `FAST` for follow-ups) → tool calls via registry → max 8 steps → final answer; streaming to client over `/ws/agent` or SSE; conversation store `agent_conversation`, `agent_message`.
2. Grounding check: post-process the answer, extract cited ids/numbers, verify they appear in this turn's tool outputs; flag unverifiable claims visibly ("unverified") rather than silently.
3. Canned analyst flows as tool compositions with templated evidence input: "why is X ranked first" (rankings + score breakdown + context), "compare A and B" (compare endpoint), "what is working today" (rankings + pnl).
4. Web `/agent`: chat with tool-call trace panel (each call, scope, latency), disabled state when `hejje.llm.enabled=false`. TUI: `hejje ai "question"` (one-shot, prints answer and tool trace) and `hejje ai` interactive mode.
5. Tests with `FixtureLlmProvider`: for five canonical questions the expected tools are called; a fabricated number is flagged.

**Verification.** `./gradlew test --tests '*HejjeAi*'`, web e2e for chat with fixture provider.

---

## M4.4 Agent-prepared orders with human confirmation (Level 3)  (size: L)

**Goal.** Agents propose; deterministic engines validate; humans approve (PRD §27 Level 3, §30, §49).

**Tasks.**
1. Tools: `prepare_order(signalId | {instrumentId, side, riskRupees, entry, stop, target})` → sized proposal with dry-run `RiskDecision` (scope `orders:prepare`); `submit_order_intent(proposal)` → creates intent with status `PROPOSED` and an `approval(id, intent_id, requested_by_session, summary, status PENDING|APPROVED|REJECTED|EXPIRED, expires_at (default 5 min or signal validity), decided_by, decided_at)`; `modify_order_intent`, `cancel_order_intent`, `close_position_intent` likewise create approvals. Audit `AGENT_RECOMMENDED`, `USER_APPROVED`.
2. Approval endpoints (`orders:execute`, human credentials only): `GET /approvals?status=`, `POST /approvals/{id}/approve` (Idempotency-Key; re-runs risk at approval time; then the normal pipeline), `POST /approvals/{id}/reject`. Push to `/ws/events`.
3. Policy engine (`risk.policy`): `Policy.decide(action, actorType, autonomyLevel, mode, context) → ALLOW | REQUIRE_APPROVAL | DENY`, table `policy_rule` seeded from PRD §49 (manual → approval; agent → approval; new strategy version → never auto; event risk HIGH → approval; daily loss > threshold → deny). `GET /risk/policies` inspectable; edits require `risk:write`. Autonomy level stored per deployment (0–3 enforced here; 4–5 rejected until Phase 5).
4. Web: approvals inbox (badge in header, approve/reject with the proposal and risk checks), Hejje AI shows "Proposal created — approve in inbox". TUI: `hejje approvals`, `hejje approve <id>`, `hejje reject <id>`; dashboard notification line.
5. Tests: agent proposal → approval → order placed in PAPER; expired approval cannot be approved; approval re-validation fails if daily loss limit hit in between; level 0/1 deployment → `DENY` for `prepare_order` on that strategy.

**Verification.** `./gradlew test --tests 'money.hejje.agent.*' --tests 'money.hejje.risk.policy.*'`

---

## M4.5 Performance investigation agent  (size: M)

**Tasks.**
1. Analytics endpoints/tools: `get_pnl_breakdown(groupBy…)` (now including `regime`, `eventContext`, `newsBias` dims from Phase 3 snapshots stored on each trade), `get_loss_attribution(period)`, `get_slippage_stats`, `get_rule_adherence`.
2. Counterfactual tool `run_counterfactual({period, filter})` → deterministic re-simulation over the actual trade set applying a filter (e.g. exclude trades where regime ∈ set, or strategy family) — results labeled `SIMULATED` everywhere with the actual figures alongside (PRD §57).
3. Prompt flow for "what lost me money this month" composing the above; web report view with actual vs simulated clearly separated; TUI `hejje ai` supports it.

**Verification.** Unit tests for attribution math; counterfactual on a fixture trade set matches hand computation.

---

## M4.6 Natural-language strategy builder  (size: M)

**Tasks.**
1. Tool `create_strategy_draft(description)` (scope `strategies:write`): LLM (profile `REASONING`, prompt `strategy_builder_v1` including the DSL doc and two examples) → YAML → schema + semantic validation → up to 3 automatic fix iterations feeding errors back → `DRAFT` version under a new or existing strategy with `change_note: "NL draft"`; returns the rule list rendered in words next to the YAML for review.
2. Lab web panel "Describe a strategy": shows draft, diff vs parent if cloned, buttons Run backtest / Edit / Discard. Lifecycle from M2.1 already prevents `DRAFT → LIVE`; add a test that proves it for NL drafts specifically.
3. Fixture-provider tests: PRD §22 sentence produces a valid draft matching expected rules.

**Verification.** `./gradlew test --tests '*StrategyBuilder*'`

---

## M4.7 Strategy experiment agent  (size: L)

**Tasks.**
1. `backtest.experiments`: `experiment(id, base_version_id, dataset spec, split spec, status, created_by_session)`, `experiment_variant(id, experiment_id, name, definition_yaml, backtest_id, rank, verdict, warnings)`; runner executes variants through the backtester (or the Python worker if M2.9 exists) with bounded parallelism.
2. Ranking (deterministic): OOS expectancy, OOS profit factor, max DD, trade count, simplicity (parameter/condition count), walk-forward stability; overfitting warnings: IS ≫ OOS gap, number of variants tested (multiple-comparison note), variants with < 100 trades, parameter values at sweep edges.
3. Tools: `propose_variants(versionId, goal)` (LLM proposes candidate filters/parameters as YAML deltas; engine validates each), `run_experiment(versionId, variants)`, `get_experiment(id)`; prompt `experiment_agent_v1` instructs preference for robustness and simplicity (PRD §24). Nothing in an experiment changes a strategy's status; promoting a variant creates a normal new version through M2.1.
4. Web Lab: experiments list, variant table with ranks and warnings, "promote to version" action; TUI read-only `hejje experiments`.

**Verification.** `./gradlew test --tests 'money.hejje.backtest.experiments.*'`; fixture-provider test for `propose_variants` producing valid deltas.

---

## Phase 4 exit checklist

- [ ] An `execution` agent key prepares an order, a human approves in the TUI, the order fills in PAPER; audit shows `AGENT_RECOMMENDED → USER_APPROVED → RISK_CHECK_PASSED → ORDER_SUBMITTED`.
- [ ] A `research` key cannot call any transactional tool.
- [ ] NL strategy drafts cannot reach LIVE without backtest, validation, and human status changes.
- [ ] `hejje.llm.enabled=false` leaves every Phase 1–3 test passing.
- [ ] LLM daily cost cap enforced and visible.
