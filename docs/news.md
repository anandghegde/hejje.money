# News ingestion and news bias (PRD §17, plan M3.4)

News is bounded context, not a trading signal: the LLM only classifies individual stories; everything that turns them
into a number is deterministic and every source item is retained as evidence. The module is **off by default**
(`hejje.news.enabled=false`, env `HEJJE_NEWS_ENABLED`) and needs the LLM (docs/llm.md) for assessments.

## Pipeline

1. **Sources** (`news_source`, seeded from `config/news-sources.yaml`, editable through `PUT /news/sources/{id}`):
   RSS, Atom or a JSON array. Each has a `reliability` 0..1 that weights its items.
2. **Polling** every `hejje.news.poll-minutes` (5): fetch, parse (HTML stripped, secure XML parsing), then
   **dedupe** by URL, by content hash (normalised title + summary) and by near-identical normalised title within
   24 h (Jaccard word overlap ≥ `title-similarity` 0.8). A failing source records its error and the others continue.
3. **Instrument matching** (deterministic first): `config/aliases.yaml` maps each instrument to company names, tickers
   and a sector; a whole-word, case-insensitive hit in title or summary makes it a candidate. No candidate → no LLM
   call (cost control; `max-items-per-poll` caps the rest).
4. **Classification** (LLM profile `news`, prompt `news_classify_v1`, JSON validated by `StructuredOutput`): per
   candidate `relevance, direction (−1..1), materiality, novelty, confidence, event_type, summary`, stored in
   `news_assessment` with the model and prompt version. Symbols the model invents are dropped. With the LLM disabled
   items are stored and nothing is assessed.
5. **Bias** (`news_bias`, computed on read by `GET /context/news-bias`): over the trailing `window` (24 h), for each
   assessment with relevance ≥ `min-relevance` (0.3):

   `contribution = direction × materiality × confidence × novelty × source reliability × 0.5^(age / half-life 4 h)`

   `score = clip(Σ contributions × confirmation, −1, 1)` with `confirmation = min(1.5, 1 + 0.25 × (distinct sources − 1))`.

   Label: ≥ 0.6 `STRONGLY_BULLISH`, ≥ 0.2 `BULLISH`, ≤ −0.6 `STRONGLY_BEARISH`, ≤ −0.2 `BEARISH`, else `NEUTRAL`.
   Evidence: one line per contributing story (direction glyph, summary, source, age, materiality, weight), the
   confirmation factor, and the **price/volume reaction** check (today's move vs the previous close and relative
   volume from M5 candles; "confirms", "disagrees with" or "does not yet confirm" the bias).
6. **Availability**: the bias is `NEUTRAL` with `available=false` and the reason in evidence when news is disabled,
   the configured classifier is disabled (the LLM, or Jev with `classifier: jev`), or no source polled successfully
   within `stale-after` (2 h). Consumers (score adjuster,
   Today) treat that as "news unavailable".

## Where it shows

- Score adjuster "News context": `round(3 × score)` bounded −3..+3, 0 when unavailable (docs/hejje-score.md).
- Recommendations carry `newsBias` (the score, null when unavailable); the Best Hejje card and the strategy page show
  the label, score and expandable source items.
- Endpoints (docs/api.md): `GET /news`, `GET /context/news-bias`, `GET /news/sources`, `PUT /news/sources/{id}`,
  `POST /news/poll` (admin).

## Jev classifier (Phase 9, M9.3)

`hejje.news.classifier` picks who assesses a story: `llm` (default, as above), `jev`, or `shadow`.

- **`jev`**: one Jev call per (story, candidate) with the question set `config/jev/news.yaml` (v1) and the state
  `{symbol, aliases, title, summary, source, publishedAt}`. The answers map into the same `news_assessment` fields, so
  the bias formula above is unchanged:

  | Field | From Jev |
  |---|---|
  | relevance | the `relevance` noul |
  | direction | P(`bullish`) − P(`bearish`) of the `direction` choice |
  | materiality | the expected level of `materiality` (Routine 0, Notable 1, Clearly price-moving 2) / 2 |
  | novelty | the expected level of `novelty` (Repeat 0, Update 1, New information 2) / 2 |
  | confidence | the `direction` answer's confidence (the chosen option's probability when there is none) |
  | event_type | the `event_type` choice (the Phase 3 list; anything else is OTHER) |
  | summary | the title (Jev returns no text) |
  | model / prompt version | `jev:<model version>` / `jev-news-v1` |

  A failed or late call leaves the candidate unassessed, exactly as a failed LLM call does (the bias stays
  `NEUTRAL`). With Jev disabled the bias is unavailable ("Jev disabled"). The LLM is not needed.
- **`shadow`**: both classify; the LLM's assessment is used, Jev's is stored beside it with `shadow=true` and never
  feeds the bias or the `/news` item list. `GET /news/classifier-comparison?from=&to=` (default the last 30 days)
  returns the pairs, the direction agreement (both labelled with the bias cut-offs: above `mild-score` bullish, below
  its negative bearish, else neutral), a direction confusion matrix (LLM → Jev), the mean absolute difference of
  materiality and relevance, and the calibration of Jev's news direction beside it.
- Switching to `jev` is allowed at any time: news is context, not a trade decision.

**Calibration**: every Jev direction answer on a relevant story (relevance ≥ `min-relevance`) is recorded as purpose
`news.direction` with the probability P(bullish) / (P(bullish) + P(bearish)) that the price moves up, labelled by
`DIRECTION_NEXT_CLOSE` (docs/calibration.md).

## Index risk events from headlines (Phase 9, M9.3)

After a poll that stored new items and with Jev enabled, one Jev call (`config/jev/news-risk.yaml` v1) reads the
newest `market-headline-count` (15) titles of the day: `risk_event_today` (noul) and `event_kind` (`RBI`, `FED`,
`BUDGET`, `CPI`, `OTHER`). At P ≥ `risk-event-threshold` (0.6) a market-wide, all-day macro event is upserted into the
calendar (`market_event`, source `news-jev`, type `RBI_POLICY`, `FED_DECISION`, `BUDGET`, `INDIA_CPI` or
`GEOPOLITICAL` for OTHER), keyed by date and kind, so the same event seen in many polls is one row. The event-risk
rules then treat it like any macro event in progress (HIGH). The first insert is audited (`EVENT_ADDED`, actor SYSTEM).
Its `raw` keeps the probability, the Jev call id and the headlines. A story still reaches Jev only after deterministic
alias matching, and `max-items-per-poll` still applies.
