# AGENTS.md: rules for AI coding agents

> Antigravity and Claude Code: read this file fully before writing code. Copy it to `CLAUDE.md` at the repo root as well.

## What you are building

SteerProof: research paper → verified claims → Steer-branded Instagram carousel, static, reel, captions → doctor approval gates → export. See `docs/PRD.md` for scope and `docs/ARCHITECTURE.md` for structure.

## Non-negotiable rules

1. **The LLM never decides provenance.** Page numbers and bounding boxes come only from Docling passages. The LLM cites a `passage_id`; code verifies the quote against that passage with rapidfuzz (`partial_ratio >= 90`). On failure, the claim status is `NEEDS_EVIDENCE`. Never fabricate a page, quote, or number.
2. **Two approval gates, enforced in the backend, not the UI.**
   - Gate 1: only claims with `doctor_status IN ('APPROVED','EDITED')` can be sent to the writer.
   - Gate 2: export returns HTTP 422 unless every claim used by every asset is approved AND every asset is `APPROVED`.
3. **Every piece of user-visible copy maps to claim IDs.** Slide text, static headline, reel scene, caption sentence. Uncited copy is allowed only for brand lines from `docs/BRAND.md` (sign-off, CTA) and must be tagged `claimIds: []` with `kind: "brand"`.
4. **Cultural examples are illustrations, never findings.** "Think of your 3pm chai crash" can't be presented as something the paper studied. The writer output marks these with `kind: "illustration"`.
5. **No paid services.** Gemini free tier, edge-tts, Pexels, local Postgres, Playwright, Remotion. Do not add OpenAI, ElevenLabs, Veo, Canva APIs, or any SDK needing billing.
6. **Cache every LLM call** to disk keyed by `sha256(model + prompt + input)`. Respect `LLM_MAX_RPM`. Reruns must cost zero requests.
7. **All LLM calls use structured output** (`response_mime_type="application/json"` + Pydantic `response_schema`) and `temperature=0` for extract/verify, `0.7` for write. Validate with Pydantic; retry once on failure; then mark the stage `FAILED` with the error stored. Never silently swallow failures.
8. **Brand tokens are constants** in one file per service (`brand.ts`, `Brand.java` if needed). No hex codes outside the palette in `docs/BRAND.md`.
9. **Deterministic guardrails run in Spring** (`PolicyChecker`) on every generated string. See `docs/GUARDRAILS.md`.
10. **Audit everything.** Every state change writes an `audit_log` row with actor, action, entity, before/after.

## Coding conventions

- **Java:** Java 21, Spring Boot 3.3+, packages by feature (`paper`, `claim`, `asset`, `review`, `policy`, `common`). Records for DTOs. `@Transactional` on service methods that change state. Use `RestClient` for internal calls. Use Flyway for all schema changes; never use `ddl-auto=update`.
- **Python:** 3.11, FastAPI, Pydantic v2, type hints everywhere, `ruff` clean. One module per stage. No global state except the cache and rate limiter.
- **Node:** TypeScript, ESM. Render service exposes REST only; it is stateless.
- **Frontend:** Next.js App Router, TypeScript, Tailwind configured with brand tokens. Server Components for data fetches, Client Components only where interactive.
- IDs are prefixed strings: `P-0001` (paper), `E-0001-0042` (passage), `C-0001-007` (claim), `A-0001-CAR` (asset).
- Times are UTC ISO-8601.
- Files are written under `STORAGE_DIR`, and their paths are stored relative to it.

## Definition of done for any task

- It compiles, tests pass, and the happy path works end to end with `demo/papers/hall-kahan-2018.pdf`.
- New endpoints are documented in `docs/API.md` if they differ from it.
- No TODOs left on the critical path (ingest → claims → gate 1 → write → render → gate 2 → export).

## Things to avoid

- Multi-agent frameworks (LangChain agents, CrewAI). Plain function calls are enough.
- Generating text inside images. All text is rendered with HTML/CSS or Remotion.
- Stock footage of bodies, scales, measuring tapes, gyms, or before/after imagery.
- Mentioning drug brand names (Ozempic, Wegovy, Mounjaro, Zepbound, Rybelsus, Saxenda) anywhere in output copy.
- ALL CAPS text in any rendered asset.
