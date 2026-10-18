# WS1 accuracy: evidence (2026-10-18)

## Root cause of the wrong citation (1.1)
1. `ml-service/app/parse/meta.py` hardcoded `Kevin D. Hall, Scott Kahan / 2018 / Med Clin North Am / 10.1016/j.mcna.2017.08.012` for every paper. WS5 fixed this in 9f9cd3d.
2. The writer then received that row as `PAPER CITATION` and wrote citation text itself: the static `footnote` and the source-slide body ("Hall and Kahan (2018)").
3. The fix (684a066 + follow-up):
   - The writer no longer receives or produces citation text. `footnote` is removed from the schema and the prompt, and the prompt forbids names, years and journals.
   - `AssetContentEnricher` sets `citation` in code from the paper's own row via `CitationHelper`. CitationHelper no longer invents `2022` or "Clinical Study".
   - The writer cache key includes `WRITER_PROMPT_VERSION | paperId | claim payload`.

## Contract added to asset content (code-built, never by the LLM)
- `content.citation` on every asset. Per block, `citation` is set only on kind=claim slides, scenes, static and caption paragraphs, plus the carousel source slide. It is null everywhere else.
- `statEligible` + `stat` on every slide, scene and static. Outcome units only (%, kg, kcal, calories, mmHg, mg/dL, mmol, lb, cm, bpm). A count like "29 studies" is never a stat.
- Reel `motion`:
  - `quote` on a non-claim block is coerced to `title`.
  - `quote` on a claim block gets the approved copy as `onScreen` (editedCopy, else consumerDraft, max 140 chars), or is coerced to `title`.
  - `stat` without an outcome value is coerced to `title`.
- `blockMap`: `slideN.label`, `slideN.footer`, `static.label`, `static.footer` and `static.footnote` are kind=brand. `slideN.stat` appears only when the slide is statEligible.
- ml-service has a new endpoint, `POST /check/illustrations`: one batched Gemini call at temperature 0, cached by model, prompt version, paperId and items. It fails closed.

## New BLOCKING rules (PolicyChecker)
| Rule | Fires on |
|---|---|
| CITATION_ON_NONCLAIM | `citation` on a non-claim block (source slide exempt); quote motion on a non-claim scene |
| CITATION_TEXT_IN_COPY | "et al", "(YYYY)", ", YYYY" or "doi" in writer copy; any legacy static `footnote` |
| QUOTE_NOT_CLAIM_COPY | Quote card text that is not the approved claim copy |
| MISSING_QUALIFIER | A block linked to an EXPLORATORY claim without a qualifier, in any format: slide headline/body, static headline/support, reel onScreen **and** voiceover, caption paragraph |
| ILLUSTRATION_STATES_FACT | A Gemini "yes" verdict, a failed check, or the keyword backstop (biology, hormones, signalling, metabolism, insulin, blood sugar, "your body is/does/…") |

MISSING_QUALIFIER details:
- Qualifiers are the spec list plus "in this/one/the [≤2 words] trial|study|follow-up|extension|analysis".
- Heuristic for the exemption: the short line (headline or onScreen) may skip the qualifier only if it is a non-factual heading and the long line carries one. A line is factual if it has a digit, a quantity word (half, most, nearly…) or a finding verb (regained, maintained, rose, fell…). Reporting verbs such as "showed" don't count.

## Tests
- Java: 51 pass plus 1 opt-in harness, on the `steerproof_test` profile. That includes the new `AccuracyRulesTest`: 14 tests, one per rule, and a test that every citation on all 4 assets of P-0001 and P-0002 matches its own paper. The ILLUSTRATION_STATES_FACT test uses a mocked LLM and the sentence "Your biology is actively signaling for fuel after calorie deficits.".
- Python: 56 pass (`tests/test_writer_accuracy_rules.py`). It covers the mocked illustration check (one batched call at temperature 0), the qualifier rules, stat eligibility, citation-text rejection and cache-key scoping.

## 1.6 Regeneration: partially done (Gemini quota)
- `gemini-3.7-flash` (the configured model) returned HTTP 429: the 20 requests/day free-tier limit is used up, and resets in about 8h.
- `gemini-2.5-flash` returned 404 ("no longer available to new users").
- `gemini-3.8-flash`, used on the private :8011 instance only, gave **one** P-0001 writer response. The validator caught "Scene 3: motion 'stat' needs an outcome value…". The retry then also got a 429.
- **P-0001**: the new output is the first attempt, recovered from the disk cache with no extra call → `docs/reports/ws1-writer-P-0001.json`.
- **P-0002**: no new writer output. `docs/reports/ws1-writer-P-0002.json` instead holds the existing P-0002 assets re-checked under the new rules.
- The illustration Gemini check could not run either, so every asset with an illustration carries the fail-closed BLOCKING violation. This is expected behaviour until the quota resets.
- Claims in the dev DB are PENDING. The dry run simulated APPROVED **in memory only**. No dev-DB writes, no approvals.

### Violations found (fail-closed "check could not run" entries omitted)
Existing P-0001 assets (written before WS1):
- CAROUSEL slide:6: CITATION_TEXT_IN_COPY "(2018)"
- CAROUSEL slide:3 and CAPTION p1: ILLUSTRATION_STATES_FACT, keyword "biology". Slide 3 is "…Your biology is actively signaling for fuel after calorie deficits."
- REEL scene:4: QUOTE_NOT_CLAIM_COPY ("Doctor support changes things")
- STATIC: CITATION_TEXT_IN_COPY (legacy footnote)
- Slide 4 "Across 29 long-term studies" → `statEligible=false` (it was rendered as a stat before)

Existing P-0002 assets (written before WS1):
- CAROUSEL slide:6: CITATION_TEXT_IN_COPY "(2018)" (the wrong Hall & Kahan citation)
- REEL scene:2: QUOTE_NOT_CLAIM_COPY ("It is biology, not *willpower*." shown in a quote card). After enrichment the scene shows the approved claim copy.
- REEL scene:4: MISSING_QUALIFIER on onScreen ("Nearly *half* maintained weight loss.") and on voiceover
- STATIC: MISSING_QUALIFIER on the headline ("Weight regain is about *biology*.") and CITATION_TEXT_IN_COPY (legacy footnote "Hall & Kahan (2018)…")

New P-0001 writer output (WS1 prompt):
- No citation text, no MISSING_QUALIFIER (all P-0001 claims are BACKGROUND), no CITATION_ON_NONCLAIM and no quote cards.
- Only the fail-closed illustration-check entries remain.
- Stats: slide 3, static and scene 2 = "100 calories" (eligible). Slide 4 and scene 3 ("29 studies"/"over half") are not eligible.
- brollQuery values: office desk laptop, kitchen counter, wall clock, morning park path, chai cup.

### Citation strings per paper (every `citation` value on every asset after enrichment)
| Paper | Carousel | Static | Reel | Caption |
|---|---|---|---|---|
| P-0001 | Hall et al., 2018 · Med Clin North Am | same | same | same |
| P-0002 | Wilding et al., 2022 · Diabetes Obes Metab | same | same | same |

P-0002 metadata came from the fixed `meta.py` (PDF + Crossref): "John P. H. Wilding, … Robert F. Kushner / 2022 / Diabetes Obes Metab / 10.1111/dom.14725". The citation follows whatever the DB row holds, so the DB row must carry this (WS5 5.2).

## Notes for integration
- Reel rendering is not part of the RENDER stage in `PipelineService` (only carousel and static are). The reel is rendered via `reRenderAsset`, which `regenerateSingleAsset` and approval call.
- Content generated before this change has no `citation`/`statEligible` fields. Regenerate after the human review (Gate 1) to get them.
- A brand hook line like "You did not fail; biology is pushing back" isn't checked for facts. The voice spec sanctions "it's biology, not willpower" as a brand message, so I left brand lines out of the illustration check.
