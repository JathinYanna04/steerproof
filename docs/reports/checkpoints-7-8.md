# Checkpoints 7 & 8 Report: Gate 2 + Export & Minimal Frontend

## SECTION A: FIXES FROM CHECKPOINTS 5–6

### 1. B-Roll & Pexels API Key
- **API Key Status in `.env`**: Evaluated `PEXELS_API_KEY` in `/home/yjr/steerproof/.env`. The key line was blank (`PEXELS_API_KEY=`).
- **Graceful Fallback**: In accordance with `docs/RENDERING.md` (page 4), when the API key is unset or a query is denylisted, SteerProof renders authentic procedural motion backgrounds using Steer brand palettes (`#FBF8F3` cream, `#E6CBBD` blush, `#3D141A` maroon) with animated drifting soft geometric shapes.
- **Credits & Licensing**: Recorded in `demo/CREDITS.md` with full attribution for typography (SIL OFL 1.1), royalty-free music (`calm-1.mp3`, Pixabay Content License), and video rendering guidelines.

### 2. Reel Length & Voiceover Budget (25–40s Target)
- **Prompt Specification**: Updated `ml-service/app/prompts/writer.md` enforcing a strict voiceover budget of `≤ 85 words` total across all 5 scenes combined (≈ 17 words per scene).
- **Automated Validation & Retry**: Updated `ml-service/app/write/validator.py` with an automated validation check asserting `total_reel_words <= 85`. If exceeded, `writer.py` automatically retries with specific error guidance.
- **Re-synthesized Audio & Re-rendered MP4s**:
  - `P-0001`: Total 84 words across 5 scenes. Voiceover synthesized via Edge TTS (`en-IN-NeerjaNeural`, rate `+18%`). Re-rendered vertical MP4 duration: **39.74 s** (within target 25–40 s).
  - `P-0002`: Total 85 words across 5 scenes. Voiceover synthesized via Edge TTS (`en-IN-NeerjaNeural`, rate `+18%`). Re-rendered vertical MP4 duration: **37.95 s** (within target 25–40 s).
- **Exact File Paths**:
  - `storage/renders/P-0001/reel.mp4` (39.74s, 1080×1920 H.264/AAC)
  - `storage/renders/P-0002/reel.mp4` (37.95s, 1080×1920 H.264/AAC)

### 3. Citation Numbers in `NUMBER_MISMATCH`
- **Root Cause**: `PolicyChecker` previously treated citation year numbers (e.g., `2022`, `2018`) and volume/page numbers in source slides as unverified statistical numbers.
- **Fix**: Updated `PolicyChecker.java` to explicitly ignore:
  1. 4-digit publication years matching `^(?:19|20)\d{2}$`.
  2. Numbers within citation contexts (adjacent to `et al`, `vol`, `pp.`, or within citation parentheses).
- **Unit Test**: Added `testCitationNumbersIgnoredInNumberMismatch` in `PolicyCheckerTest.java`. All 17 backend policy checker tests pass.
- **Resolution**: Cleared the false `NUMBER_MISMATCH` violation on `A-0002-CAR`. Status transitioned from `BLOCKED` to `RENDERED` with 0 blocking violations.

### 4. Short Citations
- **Standardized Format**: Implemented `CitationHelper.java` adhering strictly to `<First author> et al., <year> · <journal abbreviation>`.
- **Rendered Output**:
  - `P-0001`: `Hall et al., 2018 · Med Clin North Am`
  - `P-0002`: `Wilding et al., 2022 · Diabetes Obes Metab`
- Full bibliographic citations including paper titles and DOIs are preserved exclusively in `sources.md`.

### 5. Human-Only Reviews & Database Reset
- **Strict Principle**: Zero agent approvals or doctor reviews. All doctor reviews for `P-0001` and `P-0002` are reserved exclusively for the human clinician/reviewer via the UI.
- **Database Reset**: Added `keepClaims=true` to `POST /api/papers/{id}/reset`:
  - Preserved extracted and verified claims.
  - Reset `doctorStatus` to `PENDING` for all claims.
  - Cleared all reviewer fields (`doctorName = null`, `doctorNote = null`, `editedCopy = null`, `reviewSeconds = 0`, `reviewedAt = null`).
  - Reset all assets to `RENDERED` (`approvedBy = null`, `approvedAt = null`).
  - Deleted all agent `human_events` records.
  - Set both `P-0001` and `P-0002` paper statuses to `AWAITING_CLAIM_REVIEW`.
---

## SECTION B: CHECKPOINT 7, GATE 2 + EXPORT

### 1. Asset Approval Endpoint (`POST /api/assets/{id}/approve`)
- **Strict Preconditions**:
  - Asset status must be `RENDERED`.
  - Every linked claim in `asset_claims` must be `APPROVED` or `EDITED`.
  - No unresolved `BLOCKING` violations.
  - If any condition fails: Returns `422 Unprocessable Entity` (`error: ASSET_BLOCKED`) with structured details `{claims, violations, status}`.
- **Warning Override**:
  - If warning violations exist (e.g. `CERTAINTY_ON_EXPLORATORY`), approval requires `overrideWarnings: true`.
  - Overrides are audited via `auditService.log(doctorName, "WARNING_OVERRIDE", ...)`.
- **Approval Actions**:
  - Sets `status = APPROVED`, `approvedBy`, `approvedAt`, and increments `version`.
  - Audits `ASSET_APPROVED` and logs `ASSET_REVIEW` in `human_events`.
  - Automatically triggers `pipelineService.reRenderAsset(asset, false)` to re-render without watermark (`draft=false`) and update source slide text to *"Reviewed and approved for Steer"*.

### 2. Export Endpoint (`POST /api/papers/{id}/export`) & Blocked Response
- **Precondition Verification**:
  - Querying `POST /api/papers/P-0001/export` while assets are pending human review correctly returns HTTP 422:
```json
{
  "error": "EXPORT_BLOCKED",
  "message": "Paper cannot be exported because not all assets are approved",
  "details": {
    "blockedBy": [
      {
        "assetId": "A-0001-CAP",
        "type": "CAPTION",
        "status": "RENDERED",
        "reason": "RENDERED_NOT_APPROVED"
      },
      {
        "assetId": "A-0001-CAR",
        "type": "CAROUSEL",
        "status": "RENDERED",
        "reason": "RENDERED_NOT_APPROVED"
      },
      {
        "assetId": "A-0001-REEL",
        "type": "REEL",
        "status": "RENDERED",
        "reason": "RENDERED_NOT_APPROVED"
      },
      {
        "assetId": "A-0001-STA",
        "type": "STATIC",
        "status": "RENDERED",
        "reason": "RENDERED_NOT_APPROVED"
      }
    ]
  }
}
```

- **Export Package Structure**:
  On approval, creates `storage/exports/P-xxxx.zip` containing:
  - `carousel/1..n.png` (All slides rendered clean without watermarks)
  - `static.png` (+ square and story aspect ratios if available)
  - `reel.mp4` (Full 1080×1920 Remotion reel rendered without watermarks)
  - `caption.txt` (Complete post caption + hashtags)
  - `sources.md` (Table of claimId, on-post text, exact quote, page, section, short citation + media credits)
  - `review_report.json` (Structured JSON of all claim and asset approvals with reviewer, timestamps, version, and overrides)
  - `README.txt` (`Approved for publishing by <reviewer> on <date>`)

### 3. Seeded Bad-Claim Flow (`POST /api/demo/seed-bad-claim/P-0002`)
- **Execution**: Evaluated live through the real Gemini 3.7 Flash verifier in `ml-service` (not mocked):
- **Live Output**:
```json
{
  "claimId": "C-0002-BAD",
  "paperId": "P-0002",
  "passageId": "E-0002-0001",
  "scientificClaim": "Weight always comes back fully when you stop treatment.",
  "consumerDraft": "Weight always comes back fully when you stop treatment.",
  "quoteScore": 100,
  "numbersOk": true,
  "entailment": "UNSUPPORTED",
  "risk": "RED",
  "riskReason": "The statement is unsupported by the provided text and presents an inaccurate, absolute claim regarding medication discontinuation.",
  "populationFit": "MEDIUM",
  "populationReason": "The study evaluated adults with overweight or obesity, sharing relevant metabolic characteristics but drawn from a broader, predominantly non-South Asian trial cohort.",
  "doctorStatus": "PENDING",
  "isSeededBad": true
}
```
- **Result**: Successfully categorized as `UNSUPPORTED` / `RED` due to absolute claim contrary to trial findings.

### 4. Automated Integration Test
- **Test Class**: `Gate2AndExportIntegrationTest`
- **Full Sequence Verified**:
  1. Exploratory claim wrongly approved by doctor.
  2. Asset includes claim with certainty copy → policy check fires `CERTAINTY_ON_EXPLORATORY` warning.
  3. Approve without `overrideWarnings` → Rejected with 422 `ASSET_BLOCKED`.
  4. Doctor rejects bad claim → linked asset automatically transitions to `STALE`.
  5. Export attempted while asset is `STALE` → Rejected with 422 `EXPORT_BLOCKED` (`blockedBy` shows STALE).
  6. Asset regenerated without bad claim → transitions to `RENDERED` with 0 violations.
  7. Asset approved by human reviewer → transitions to `APPROVED`.
  8. Export succeeds with 200 OK → generates `storage/exports/P-xxxx.zip` with `README.txt`, `sources.md`, `review_report.json`, `caption.txt`.
- **Result**: `Gate2AndExportIntegrationTest` passed in 4.29s.

### 5. Full Test Suite Results
- **Java Backend Test Suite**:
```
[INFO] Tests run: 34, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
- **Python ML Service Test Suite**:
```
============================== 23 passed in 1.63s ==============================
```
