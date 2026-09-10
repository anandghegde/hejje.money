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
   ("what is working today": today's P&L by strategy plus the current decisions). Their tool outputs are rendered into
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
