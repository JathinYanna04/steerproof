# Testing

## Python (pytest)
- `test_quote_check.py`
  - exact quote → 100; whitespace/case/en-dash differences → ≥ 95
  - a paraphrase with the same meaning → < 90 (must fail)
  - a quote found in the neighbour passage → re-linked
- `test_number_check.py`
  - "10.0%" vs "10.0 %" ok; "−5.2" vs "-5.2" ok; "11.6" vs "11.8" fails; written numbers one–ten skipped
- `test_extractor_postprocess.py`: unknown passageId dropped and logged
- `test_writer_validation.py`: claim block without claimIds → error; illustration with "%" → error; over-length headline → error
- `test_cache.py`: second identical call → cache hit, 0 API calls (mock the client)
- `test_broll_denylist.py`: "belly fat" blocked, "chai cup" allowed

Mock Gemini in unit tests. Add one optional `-m live` test that hits the real API on a short abstract.

## Java (JUnit 5 + Testcontainers Postgres)
- `PolicyCheckerTest`: one test per rule code in `docs/GUARDRAILS.md`, with positive and negative cases. Include "GLP-1" passing the caps rule and "MIRACLE" failing.
- `ReviewServiceTest`
  - Gate 1 blocks with pending claims, NEEDS_EVIDENCE claims, or fewer than 3 approved
  - APPROVE on NEEDS_EVIDENCE → 409
  - Rejecting a claim used in an asset → asset STALE
  - asset approve refused while BLOCKED/STALE or while a linked claim is not approved
  - export 422 lists each blocker; success writes audit + EXPORTED
- `AuditLogTest`: every state change creates a row; there is no update/delete path.

## Golden end-to-end test (`demo/e2e.sh` or a Java integration test)
1. Upload `demo/papers/hall-kahan-2018.pdf`.
2. Wait for `AWAITING_CLAIM_REVIEW`. Assert ≥ 6 claims, and that every claim has a passage with a bbox, or that its status is NEEDS_EVIDENCE.
3. Approve all GREEN/AMBER claims, reject RED ones.
4. Generate. Wait for `AWAITING_ASSET_REVIEW`. Assert 4 assets, with carousel PNG count 5–7 and a reel mp4 of 20–45 s.
5. Approve all, export, and assert the zip contains every expected file.

## The blocked-claim demo test (must pass; it is the Loom climax)
`demo/seed_bad_claim.sql` (or endpoint `POST /api/demo/seed-bad-claim/{paperId}`, enabled only with `DEMO_MODE=true`) inserts into the STEP 1 extension paper:
- scientific claim: "Weight always comes back fully when you stop treatment."
- passage: the real passage about weight regain after withdrawal
- `is_seeded_bad=true`

Expected:
1. The verifier returns `OVERCLAIM`, risk `RED` (the source reports partial regain on average in an exploratory extension, and "always"/"fully" is unsupported).
2. If the doctor wrongly approves it and generation runs, `PolicyChecker` flags `CERTAINTY_ON_EXPLORATORY`.
3. The doctor then rejects it → the asset goes STALE → export returns 422 with the blocker → regenerate → approve → export succeeds.

Write this as an automated test with the LLM response mocked to the expected verifier output, and also run it live once for the recording.

## Manual QA checklist (before recording)
- [ ] Fonts render (no fallback serif) in PNGs and the reel
- [ ] No hex colours outside the palette (grep the templates)
- [ ] No ALL CAPS anywhere in the rendered output
- [ ] Every rendered text block is clickable and opens the right passage
- [ ] Reel subtitles are in sync (±150 ms) and readable on a phone
- [ ] B-roll shows no bodies or people's faces in close-up
- [ ] Caption ends with steerclub.com; hashtags are lowercase
- [ ] The draft watermark is gone after approval
- [ ] The rerun of a paper shows `cacheHits > 0, llmCalls = 0`
