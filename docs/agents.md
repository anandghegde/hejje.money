# Agents: tools, credentials, sessions (PRD 28-30, 48.3; plan M4.2)

Agents operate Hejje only through typed tools (`docs/agent-tools.md`, generated from the registry). A tool never calls a
broker method: it calls the same internal services the web and TUI use, and transactional tools (M4.4) only create
proposals that a human approves.

## Enforcement at the tool boundary

For every call `AgentToolService`:

1. checks the tool's required scope against the **caller's credential** (API key scopes, or the local user's JWT). Nothing in
   a prompt or in the tool input can widen it;
2. validates the input against the tool's JSON schema (`additionalProperties: false`, so smuggled fields are rejected);
3. runs the handler with a typed input record;
4. validates the output against the schema generated from the output record (outputs are serialized without nulls);
5. records an `agent_action` row (tool, input, output summary, `scope_ok`, status, latency, correlation id) and an
   `AGENT_TOOL_CALLED` audit event (actor `AGENT`, actor id = the credential's name) — refused calls included.

Statuses: `OK`, `INVALID_INPUT` (400), `FORBIDDEN` (403), `NOT_FOUND` / `UNKNOWN_TOOL` (404), `CONFLICT` (409),
`FAILED` (422), `INVALID_OUTPUT` (500), `UNAVAILABLE` (503).

Tool scopes mirror the REST endpoints they correspond to: market context, positions, orders and trades need
`market:read`; strategies, rankings, backtests and signals `strategies:read`; account risk and sizing `risk:read`; the
audit trail `admin`.

## Credential presets

`POST /api/v1/auth/clients` accepts either explicit `scopes` or a `preset`:

| Preset | Scopes |
|---|---|
| `research` | `market:read strategies:read` |
| `execution` | `market:read strategies:read orders:prepare` |

No preset contains `orders:execute`, `orders:cancel`, `positions:close`, `risk:write` or `admin`: agents prepare, humans
approve (Automation Level 3).

## Sessions

Every call belongs to an `agent_session` (credential, purpose, optional LLM profile, start/end). Direct REST calls use
the caller's `direct` session for the day unless `X-Agent-Session` names one of the caller's open sessions; MCP calls use
an `mcp` session; Hejje AI conversations (M4.3) open a `chat` session each. `GET /api/v1/agents/sessions` and
`GET /api/v1/agents/sessions/{id}` (own sessions; admin sees all) return the trace.

## Endpoints

- `GET /api/v1/agents/tools` — catalog: name, description, required scope, transactional flag, input and output schemas.
- `POST /api/v1/agents/tools/{name}` — invoke with the caller's credential; body = tool input. Errors are problem+json with
  `toolStatus`, `errors`, `actionId`.
- `POST /mcp` — the same registry as an MCP server (streamable HTTP, JSON responses): `initialize`, `ping`,
  `tools/list` (only tools the key may call), `tools/call`.

## Using Hejje tools from Claude Code (MCP)

```bash
# a research key (read-only tools)
curl -s -X POST https://<HEJJE_DOMAIN>/api/v1/auth/clients -H "Authorization: Bearer $ADMIN_TOKEN" \
     -H 'Content-Type: application/json' -d '{"name":"claude-code","preset":"research"}' | jq -r .key
claude mcp add --transport http hejje https://<HEJJE_DOMAIN>/mcp --header "Authorization: Bearer hejje_..."
```

The key is shown once; revoke it with `DELETE /api/v1/auth/clients/{id}`.

## Hejje AI chat (plan M4.3)

`HejjeAiService` answers a question in a `chat` agent session (one per conversation):

1. **Canned analyst flows** run first when the question matches (or `flow` is given): `why_ranked_first`
   ("why is X ranked first": rankings, then the chosen row's score breakdown and rules), `compare` ("compare A and B":
   two strategies on their latest versions, or two versions of one strategy with `slug v2` / `slug v3`), `working_today`
   ("what is working today": today's P&L by strategy plus the current decisions), `losses` ("what lost me money this
   month": loss attribution, slippage, rule adherence and a counterfactual for the largest family × trend loss bucket,
   the counterfactual always labelled SIMULATED and kept apart from the ACTUAL lines). Their tool outputs are rendered into
   a templated evidence block appended to the question.
2. **Tool loop**: the model (profile `reasoning` for the first question of a conversation, `fast` for follow-ups) is
   offered only the tools the caller's credential may call; each tool call goes through `AgentToolService` (scope,
   schemas, audit), at most `max-steps` (8) LLM steps. Tool results are fed back as JSON (truncated at
   `max-tool-result-chars`), refusals as `{"status":"FORBIDDEN",…}`.
3. **Grounding check**: the final answer's numbers must appear in this turn's tool outputs (rounding and fraction →
   percentage allowed) and cited ids must be ids those outputs contain. Anything else is returned as
   `grounding.unverifiedNumbers` / `unknownIds` and shown as "unverified" by the web and TUI; dates, times, ids and bare
   integers below 10 are not treated as claims.

The system prompt is `resources/prompts/hejje_ai_v1.txt` (role, PRD 66E non-responsibilities, tool rules, citation rule);
its version is logged with every LLM call. Questions and answers are stored in `agent_conversation`/`agent_message`
(with the grounding and tool trace); tool inputs/outputs stay in `agent_action`.

With `hejje.llm.enabled=false` (the default) the chat reports itself disabled (`GET /api/v1/agents/ai/status`) and
nothing else changes.

Clients: web `/agent` (streamed answer, tool-call trace with scope/status/latency, unverified numbers highlighted,
quick prompts for the flows) and TUI `hejje ai "question"` (one-shot, `--flow`, `--json`) or `hejje ai` (interactive;
`/new`, `/quit`).

## Agent-prepared orders and approvals (plan M4.4, PRD 27 Level 3)

Agents propose; Hejje's deterministic engines size and check; a human decides.

- `prepare_order` (`orders:prepare`, no side effects): from an active signal (`signalId`, sized by the signal's own dry
  run) or from `instrument`, `side`, `riskRupees`, `stop` (+ `entry`, default the last price, `target`, `product`,
  `strategy`). Returns the quantity from the position sizer, the dry-run risk decision with every check, and the policy
  decision. A DENY is returned as tool status `DENIED` (HTTP 403) with the reason.
- `submit_order_intent` (same input + `rationale`): re-prepares server-side (the agent's numbers are never trusted),
  refuses when policy denies, risk would reject or the order cannot be sized, else creates an `order_intent` with status
  `PROPOSED` and a `PENDING` approval, audited as `AGENT_RECOMMENDED`. With an `Idempotency-Key` the same request
  returns the same approval.
- `modify_order_intent`, `cancel_order_intent`, `close_position_intent` create approvals the same way.
- An approval expires after `hejje.agent.approvals.ttl` (5 minutes), or with the signal's validity for signal proposals.

Approving (`POST /api/v1/approvals/{id}/approve`, `orders:execute` + `Idempotency-Key`):

1. only a `PENDING`, unexpired approval of the server's mode can be approved; an agent credential cannot approve its
   own proposal (the local user can approve what Hejje AI proposed on their behalf);
2. policy is re-evaluated with fresh context and, for new orders, risk is re-run on the proposed order; a failure marks
   the approval `FAILED` (422, `APPROVAL_FAILED` audit) and nothing is sent;
3. the approval is claimed atomically (`USER_APPROVED` audit), then executed through the normal pipeline: signal
   proposals through the signal (so the strategy's stop order is placed), manual ones as an intent with reason
   `AGENT_PROPOSAL`, modify/cancel/close through the execution engine. The result (order id, executed intent) is stored;
   replaying the same key returns it.

Rejecting (`POST /api/v1/approvals/{id}/reject`, `{ "reason": "…" }`) is final (`USER_REJECTED`). Expired, rejected and
failed proposals leave their intent `DECLINED`. Every change is pushed on `/ws/events` as `{ "type": "approval", "id",
"status", "kind", "summary" }`.

The audit trail of an approved agent order reads `AGENT_RECOMMENDED → USER_APPROVED → RISK_CHECK_PASSED →
ORDER_SUBMITTED`. The approval policy itself is documented in `docs/risk.md`.

## Natural-language strategy builder (plan M4.6, PRD 22)

`create_strategy_draft` (tool, `strategies:write`) and `POST /api/v1/strategies/drafts` (the Lab's "Describe a strategy"
panel) turn a description into a strategy definition:

1. the LLM (profile `reasoning`, temperature 0, prompt `strategy_builder_v1`) gets the DSL reference — `docs/strategy-dsl.md`,
   copied into the jar at build time so the prompt never drifts from the doc — and two bundled examples (`nifty_orb`,
   `vwap_reversion`), and replies with YAML;
2. Hejje validates it with the same parser and semantic rules as every other definition; errors (`path: message`) are fed
   back and the model gets up to three fixes;
3. a valid definition is saved as a **DRAFT** version with change note `NL draft`: a new strategy (its name gets `_nl`,
   `_nl2`, … if taken) or, with `strategy`, that strategy's next version (the name is forced to the strategy's slug);
4. the result carries the YAML, the rules in words (`RuleWords`, rendered from the parsed definition), every attempt with
   its errors, and the parent version's YAML for the Lab's diff.

Nothing in the builder or the tool registry changes a strategy's status: a draft still needs a backtest, a validation
backtest and human status changes before it can be deployed (M2.1 lifecycle). The Lab panel offers Run backtest, Edit
(opens the version in the editor) and Discard (retires the version).

## Strategy experiments (plan M4.7, PRD 24)

`propose_variants` (`strategies:read`) asks the LLM (profile `research`, prompt `experiment_agent_v1`) for 3–6 variants of a
version as deltas, preferring out-of-sample improvement, robustness and simplicity over in-sample return; Hejje validates
every delta and reports invalid ones with their errors. `run_experiment` (`strategies:write`) backtests the baseline and the
variants with the deterministic engine and ranks them (`docs/backtesting.md`); `get_experiment` reads the result. The model
proposes, the backtester decides, and promoting a variant to a version is a human action in the Lab.
