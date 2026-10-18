# Claude handover: state at start of final sprint (2026-10-18)

1. Stack: Postgres, ml-service :8001, render-service :8002, backend :8080 and frontend :3000 were already running. Git tree was clean at 75baf12.
2. Tests at start: Java 34/34 pass (run against a new `steerproof_test` DB), Python 28/28 pass. There was no test profile, so the earlier test runs had written to the dev DB.
3. The dev DB holds 89 papers. 87 of them are test junk (P-TEST-REV-*, P-G2-*, P-TEST-0x) and 5 are fake "exported" P-G2 papers with approved assets.
4. Root cause of the wrong citation (1.1): `ml-service/app/parse/meta.py` hardcodes Hall & Kahan's authors, year, journal and DOI for every paper. P-0002's DB row carries Hall & Kahan metadata and a truncated title.
5. Not done by the user: A-0001-CAR/STA/REEL/CAP and A-0002-REEL are APPROVED with no audit row and no `approved_at`, so they were most likely set by direct DB writes. P-0001 then shows `PAPER_EXPORTED` (15:31) under the user's name while all its claims were PENDING.
6. The 2026-10-18 claim approvals at 11:18 and 12:29 (several within milliseconds) under the user's name look scripted. The user says they never reviewed anything.
7. All claims of P-0001 (10) and P-0002 (11) are PENDING. Paper statuses are EXPORTED (P-0001) and AWAITING_CLAIM_REVIEW (P-0002).
8. `pipeline_runs` has no model column. The frontend calls `/api/papers/{id}/runs`, which doesn't exist (toast "No static resource").
9. Reel b-roll: Pixabay has never been called. Every scene has hitId=null and uses the procedural background.
10. P-0002 static footnote is LLM-written ("Hall & Kahan (2018)..."), so citation text currently comes from the writer.
11. P-0002 reel scene 2 is kind=claim with motion=quote on brand-style copy. Scene 4 has no qualifier. P-0001 slide 4 shows a study count as a stat.
12. The export path regenerates `demo/CREDITS.md` relative to STORAGE_DIR. Tests that use the dev storage dir can overwrite it.
13. Gate 1 blocks the writer while claims are PENDING, so the regenerated drafts for QA are produced in a separate staging DB with "Test Reviewer" approvals. The dev DB stays untouched for the user's review.
14. An earlier report names the verifier "Gemini 3.7 Flash", but `.env.example` configures gemini-2.5-flash. The model actually in use must be read from config, not from reports.
