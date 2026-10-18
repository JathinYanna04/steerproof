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
