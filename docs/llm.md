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
      local:
        type: openai-compatible
        base-url: http://localhost:1234/v1
    profiles:                            # workload → provider (+ model, temperature, max-tokens)
      fast:      { provider: local }
      reasoning: { provider: primary }
      news:      { provider: primary, temperature: 0.0, max-tokens: 600 }
      research:  { provider: primary, model: larger-model }
    retries: 2                           # on timeouts, 429 and 5xx; backoff doubles from 500 ms
    pricing:                             # rupees per million tokens, for the cost estimate in the call log
      model-name: { input-per-million: 100, output-per-million: 300 }
```

`type: fixture` is a deterministic provider for tests and offline development (`FixtureLlmProvider`: answers
registered by prompt hash or substring). Native adapters (Gemini, Anthropic) arrive with M4.1.

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
