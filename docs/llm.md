# LLM provider abstraction (PRD §66C, plan M3.4 / M4.1)

The `llm` module is the only place that talks to a language model. It is **off by default**
(`hejje.llm.enabled=false`, env `HEJJE_LLM_ENABLED`) and nothing in the trading core depends on it (PRD 66E): callers
catch `LlmException.Unavailable` and degrade (the news bias becomes NEUTRAL / unavailable, the agent is absent).

## Providers and profiles

```yaml
hejje:
  llm:
    enabled: true
    providers:
      primary:
        type: openai-compatible          # any /chat/completions endpoint: OpenAI, OpenRouter, vLLM, LM Studio, gateways
        base-url: https://api.example.com/v1
        api-key-env: HEJJE_LLM_API_KEY   # the key is read from this environment variable, never from config
        model: model-name
        timeout: 30s
      gemini:
        type: gemini                     # native Gemini API (generateContent / streamGenerateContent)
        api-key-env: HEJJE_GEMINI_API_KEY
        model: gemini-2.5-flash          # base-url defaults to https://generativelanguage.googleapis.com/v1beta
      local:
        type: openai-compatible
        base-url: http://localhost:1234/v1
    profiles:                            # workload → provider (+ model, temperature, max-tokens)
      fast:      { provider: local }
      reasoning: { provider: primary }
      news:      { provider: primary, temperature: 0.0, max-tokens: 600 }
      research:  { provider: primary, model: larger-model, fallback: reasoning }
    retries: 2                           # on timeouts, 429 and 5xx; backoff doubles from 500 ms with ±50% jitter
    daily-cost-cap: 200                  # rupees per IST day; reaching it disables the LLM until tomorrow
    circuit-breaker: { failure-threshold: 5, open-for: 60s }
    pricing:                             # rupees per million tokens, for the cost estimate in the call log
      model-name: { input-per-million: 100, output-per-million: 300 }
```

`type: fixture` is a deterministic provider for tests and offline development (`FixtureLlmProvider`: answers
registered by prompt hash or substring, or a scripted `responder` that may return tool calls; streams word by word).
An Anthropic adapter is a possible later addition (PRD 66C); nothing outside the `llm` module sees provider-specific
request or response shapes.

## Conversations, tools and streaming

`LlmRequest` carries either a single `userPrompt` or a conversation (`messages`: user, assistant turns with tool calls,
tool results) plus `tools` (name, description, JSON schema of the arguments). `LlmResponse` returns the text and/or
`toolCalls` (id, name, parsed arguments) and the `finishReason`. The OpenAI adapter maps these to `messages`/`tools`/
`tool_calls`/`role: tool`; the Gemini adapter to `contents` with `functionCall`/`functionResponse` parts and
`functionDeclarations` (schemas reduced to the OpenAPI subset Gemini accepts; a `["x","null"]` type becomes
`type: x, nullable: true`).

`LlmService.stream(request, onDelta)` streams natively (OpenAI SSE with `stream_options.include_usage`, Gemini
`streamGenerateContent?alt=sse`), passes text deltas to the callback and returns the complete response. A failure after
the first delta is not retried and does not fall back (the client has already seen part of the answer).

## Resilience and budget

- **Retries**: timeouts, I/O errors, 429 and 5xx are retried `retries` times with doubling backoff and ±50% jitter.
- **Circuit breaker** (per provider): `failure-threshold` consecutive retryable failures open the circuit for
  `open-for`; calls fail fast with `Unavailable` meanwhile; then one trial call is let through (success closes the
  circuit, a failure re-opens it). Non-retryable failures (400, 401, invalid output) mean the provider answered and do
  not count.
- **Fallback profile**: `profiles.<name>.fallback` names a profile tried once when the primary fails or its circuit is
  open. The call log records both attempts under their own profile names.
- **Daily cost cap**: before every call the day's (IST) estimated spend is summed from `llm_call`; at or above
  `daily-cost-cap` rupees every call fails with `BudgetExceeded` (an `Unavailable`, so callers degrade) until the next IST
  day, and one `LLM_BUDGET_EXCEEDED` audit event per day is the alert. The estimate needs `pricing` for the models in use;
  unpriced calls count as zero.
- **Temperature**: structured (JSON-mode) requests default to temperature 0 unless the profile or request sets one.

`GET /api/v1/agents/llm/status` shows providers (circuit, last error, whether the key env var is set), profiles and
today's usage against the cap; `POST /api/v1/agents/llm/test` (admin) sends a trivial prompt through a profile.

## Calls, logging, structured output

- `LlmService.complete(LlmRequest)` routes by profile, applies the profile's model/temperature/max-tokens defaults,
  retries retryable failures and writes one `llm_call` row per attempt: profile, provider, model, purpose,
  prompt version, **prompt hash** (never the prompt text), input/output tokens, cost estimate (paise), latency,
  correlation id, status (`OK | RETRY | FAILED`), error. Secrets are never logged: the API key lives only in the
  process environment and the Authorization header.
- Prompts are versioned files under `resources/prompts/<name>_v<n>.txt` (`Prompts.load/fill`); the version is
  logged with every call so behaviour changes are traceable.
- `StructuredOutput.ask(profile, purpose, version, system, user, schema)` asks for JSON (`response_format: json_object`
  where supported), extracts the first JSON object from the answer, validates it against a JSON-schema subset
  (`type, properties, required, enum, minimum, maximum, items`), and retries once with the validation errors appended
  before failing.

## Consumer subscription caveat

A ChatGPT or Gemini consumer subscription does not provide API access; configure explicit API credentials or a
local/private OpenAI-compatible endpoint.
