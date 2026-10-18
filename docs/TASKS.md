# Build checklist (single ordered list)

Work top to bottom. Each item is small enough for one agent task. Tick it off when its acceptance check passes.

## Repo and infra
- [x] Create the monorepo layout from README.md; add `.gitignore` (storage/, .cache/, .env, node_modules, target, .venv)
- [x] `docker-compose.yml` with Postgres; `.env.example`
- [x] Copy AGENTS.md → CLAUDE.md

## Backend foundation (Spring Boot)
- [x] Spring Boot 3 project (web, validation, data-jpa, flyway, postgresql, actuator, lombok optional)
- [x] Flyway `V1__init.sql` from docs/DATA_MODEL.md
- [x] Entities + enums + repositories
- [x] `IdGenerator` for P-/C-/A- IDs
- [x] `AuditService` (append-only) used by every state change
- [x] `StorageService` (paths relative to STORAGE_DIR) + `/files/**` static handler
- [x] Global exception handler returning `{error,message,details}`
- [x] CORS for localhost:3000

## ml-service foundation
- [x] FastAPI app, config, schemas, `/health`
- [x] LLM client with disk cache, token bucket, retries, 429 backoff, optional Groq fallback
- [x] `/fetch-url` (direct PDF + PMC page → PDF link)
- [x] `/parse` with Docling + PyMuPDF fallback + page PNGs + parsed cache
- [x] Meta extraction (heuristics + LLM fallback)

## Extraction and verification
- [x] Prompt files from docs/PROMPTS.md; loader with `{{var}}` replacement
- [x] `/extract` + post-processing (drop unknown passage IDs, dedupe, chunking for long papers)
- [x] `quote_check.py` + neighbour re-link
- [x] `number_check.py`
- [x] `/verify` (code checks + batched Gemini verifier + rubric + population profile)
- [x] Python unit tests from docs/TESTING.md

## Pipeline orchestration (backend)
- [x] `PipelineService` with @Async stages: PARSE → EXTRACT → VERIFY, recording `pipeline_runs`
- [x] Paper status transitions + FAILED with stage/error + `/retry`
- [x] `POST /api/papers` (file + url) and `GET` endpoints
- [x] Persist passages and claims; set NEEDS_EVIDENCE by quote score

## Gate 1
- [ ] `PATCH /api/claims/{id}` with the action rules + re-verify on EDIT with a passageId
- [ ] `ReviewService.canWrite` + `POST /api/papers/{id}/generate` 422 shape
- [ ] `human_events` logging of review seconds

## Writer and policy
- [ ] `/write` with validation + one retry with errors
- [ ] `PolicyChecker` + `rules.yaml` + every rule in docs/GUARDRAILS.md
- [ ] Save assets + `asset_claims` + violations; BLOCKED on blocking violations
- [ ] Java tests for every policy rule

## Rendering
- [ ] render-service skeleton (express, brand.ts, fonts bundled)
- [ ] `carousel.html` + `static.html` templates for every slide type in docs/RENDERING.md
- [ ] Playwright shoot + block rectangles + draft watermark
- [ ] `/reel/audio` (edge-tts + words) in ml-service
- [ ] `/reel/broll` (Pexels + denylist + cache) in ml-service
- [ ] Remotion composition: scenes, subtitles, b-roll layer, music, wordmark, citation
- [ ] `/reel` render endpoint with queue
- [ ] Backend RENDER stage calling everything; assets → RENDERED
- [ ] ffmpeg fallback script (only if Remotion is blocked)

## Gate 2 and export
- [ ] `POST /api/assets/{id}/approve` with the rules; approval re-renders without the watermark
- [ ] Claim change → linked assets STALE; `/regenerate` per asset
- [ ] `POST /api/papers/{id}/export` → zip with sources.md, review_report.json, caption.txt; 422 blockers
- [ ] `ReviewServiceTest` all green

## Frontend
- [ ] Next.js + Tailwind brand theme + fonts + `lib/api.ts`
- [ ] `/` upload + list
- [ ] `/papers/[id]` stepper + stats + retry
- [ ] `/papers/[id]/claims` console with source viewer, highlight, keyboard shortcuts, timer, edit/re-link
- [ ] `/papers/[id]/assets` previews with clickable blocks, reel scene sync, caption, violations, reasoning, approve/regenerate
- [ ] `/papers/[id]/export`

## Demo hardening
- [ ] Seed-bad-claim endpoint (DEMO_MODE) + automated test
- [ ] Golden e2e on Hall & Kahan
- [ ] Run all 3 papers; fix extraction/render issues; tune prompts
- [ ] Metrics CSV export
- [ ] Manual QA checklist in docs/TESTING.md
- [ ] Record the full run + the 2-minute Loom

## Bonus (only after everything above works)
- [ ] Batch upload + week plan + `.ics`
- [ ] Hinglish variant + meaning check + Hindi TTS
- [ ] Static auto-resize (1080×1080, 1080×1920)
- [ ] Extra posts from the same papers
