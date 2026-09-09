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
   the LLM is disabled, or no source polled successfully within `stale-after` (2 h). Consumers (score adjuster,
   Today) treat that as "news unavailable".

## Where it shows

- Score adjuster "News context": `round(3 × score)` bounded −3..+3, 0 when unavailable (docs/hejje-score.md).
- Recommendations carry `newsBias` (the score, null when unavailable); the Best Hejje card and the strategy page show
  the label, score and expandable source items.
- Endpoints (docs/api.md): `GET /news`, `GET /context/news-bias`, `GET /news/sources`, `PUT /news/sources/{id}`,
  `POST /news/poll` (admin).
