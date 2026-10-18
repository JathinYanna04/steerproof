# PRD: SteerProof

## 1. Problem

Steer (a doctor-led metabolic health and weight-care clinic in India) has research papers that never become content. Posts are made by hand, mostly static, at irregular cadence, and doctor review is slow because every claim is checked from scratch.

## 2. Goal

A repeatable engine: **paper in, ready-to-post content out, with the science intact**, and doctor review reduced to checking pre-linked evidence.

## 3. Users

| User | Needs |
|---|---|
| **Content operator** (Steer marketing) | Upload papers, pick a theme, get finished assets quickly |
| **Reviewing doctor** | See each claim beside its exact source passage, approve/edit/reject fast, trust that nothing unapproved ships |
| **Audience** (regular person in India) | Clear, warm, non-judgmental explanations tied to everyday Indian life |

## 4. Scope

### Must have
1. **Ingest:** upload a PDF or paste a URL (direct PDF link or PMC article page that links a PDF).
2. **Parse:** Docling → passages with `page`, `bbox`, `section`, `text`. Page images rendered for highlighting.
3. **Extract:** Gemini outputs claims citing `passage_id` + verbatim quote + study context (design, population, finding class, numbers, limitations).
4. **Verify provenance in code:** fuzzy match quote vs passage; numbers in claim must appear in quote.
5. **Verify meaning:** separate Gemini call scores entailment, risk (GREEN/AMBER/RED), and Indian population fit.
6. **Gate 1:** doctor console to approve / edit / reject claims.
7. **Write:** Steer-voice copy for carousel (5–7 slides), static (headline + support line), reel script (5 scenes, 25–40 s), caption, and hashtags. Every element carries `claimIds` and `kind` (`claim` | `illustration` | `brand`).
8. **Policy check:** deterministic banned-terms, caps, length, and number checks on all copy.
9. **Render:** carousel PNGs (1080×1350), static PNG (1080×1350), reel MP4 (1080×1920, 30 fps) with edge-tts voiceover, word-synced subtitles, Pexels b-roll, Steer motion typography.
10. **Gate 2:** doctor approves each rendered asset; export blocked (422) until complete.
11. **Export:** zip with PNGs, MP4, `caption.txt`, `sources.md` (claim → quote → page), `review_report.json`.
12. **Traceability UI:** click any text on a rendered asset preview → opens source passage with page highlight.
13. **Metrics:** per paper, record pipeline duration per stage, claims extracted, claims auto-flagged, claims edited/rejected, doctor review seconds, and total human seconds.

### Bonus (build after the must-haves work)
- **Batch:** upload up to 5 papers → queue → "week plan" view (Mon–Sun, one asset per day).
- **Hinglish variant:** writer re-runs on approved claims with the Hinglish voice spec + `hi-IN` TTS voice. Semantic check against English claims.
- **Auto-resize:** static also rendered at 1080×1080 and 1080×1920 (story).
- **Content calendar:** schedule dates stored and exported as `.ics` (no real posting).

### Out of scope
Real Instagram publishing, paid APIs, model training, user accounts beyond a simple doctor name field, paywalled PDFs.

## 5. User stories and acceptance criteria

**US-1 Upload.** As an operator, I upload a PDF.
- AC: Paper row created with status `PARSING`. The UI shows live stage progress (polls every 2 s). Non-PDF or a >30 MB file is rejected with a clear message.

**US-2 See claims with evidence.** As a doctor, I see each claim beside its source.
- AC: Each claim card shows scientific claim, consumer copy draft, finding class badge, risk light, population-fit chip, limitations, and a "view source" button opening the page image with the bbox highlighted. Claims with failed quote verification show `NEEDS EVIDENCE` and cannot be approved; they can only be edited (re-linked) or rejected.

**US-3 Approve claims (Gate 1).**
- AC: Approve / Edit (text box; edit re-runs the deterministic checks) / Reject (reason required). The "Generate content" button stays disabled until ≥3 claims are approved and none are `PENDING`. The time a claim card was open counts toward `doctor_review_seconds`.

**US-4 Generate assets.**
- AC: Writer runs only on approved claims. Policy violations are shown inline; any `BLOCKING` violation keeps the asset in `BLOCKED`. Renders appear as previews.

**US-5 Click a claim on an asset.**
- AC: Hovering a text block on a preview outlines it, and clicking opens the linked claim(s) and source passage. `illustration` blocks show "Illustration, not a study finding." `brand` blocks show "Brand line."

**US-6 Approve assets (Gate 2) and export.**
- AC: Export returns 422 listing `blockedBy` (claim/asset IDs + reason) until all are approved. A successful export downloads the zip, writes an audit row, and stamps the paper `EXPORTED`.

**US-7 Reject cascades.** If a doctor rejects or edits a claim after rendering, every asset using it becomes `STALE` and must be regenerated before approval.

**US-8 Metrics.** The paper page shows a stats card with the metrics in Must have #13, and `demo/metrics.csv` is exportable.

## 6. Non-functional
- One paper, ingest to rendered previews, in under 6 minutes on a laptop (excluding human review).
- Rerun of the same paper uses the cache: zero LLM requests.
- Every failure is visible as a stage state with an error message; nothing hangs silently.

## 7. Judging map

| Criterion (weight) | How SteerProof scores |
|---|---|
| Pipeline (40%) | One upload drives every stage automatically; only two human clicks-gates; batch queue; cached reruns; stage states and retries |
| Accuracy (25%) | Passage-ID provenance verified by code, number matching, a separate verifier, finding class propagated, the blocked-claim demo |
| Content quality (25%) | Code-rendered exact brand system, mixed serif headlines, Indian-life illustrations, warm edge-tts Indian voice, word-synced subtitles |
| Smart tech (10%) | Docling bboxes → click-to-source, structured outputs, rapidfuzz verification, Remotion data-driven reels, zero-cost stack |
