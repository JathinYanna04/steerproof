# SteerProof

**A doctor-gated, evidence-traceable pipeline that turns metabolic-health research papers into on-brand Instagram content for Steer (steerclub.com).**

Paper in (PDF or link) → verified claims → Steer-voice carousel, static post, AI-voiced reel, captions and hashtags → doctor approval → export.

Built for **Steer Hackathon, Problem Statement 2: "From research paper to Instagram, automated."**

The whole stack is **free**: no paid APIs, no credit card.

---

## The one-line pitch

> Every sentence on every slide clicks back to the exact passage in the paper it came from, and nothing exports until a doctor approves it.

---

## Documentation index (read in this order)

| File | What it covers |
|---|---|
| `AGENTS.md` | **Rules for AI coding agents (Antigravity / Claude Code). Read first.** Copy it as `CLAUDE.md` too. |
| `docs/PRD.md` | Product requirements, user stories, acceptance criteria, judging map |
| `docs/ARCHITECTURE.md` | Services, data flow, state machines, docker-compose, env vars |
| `docs/DATA_MODEL.md` | PostgreSQL schema (Flyway migration), enums, invariants |
| `docs/API.md` | Every REST endpoint: public (Spring) and internal (ml-service, render-service) |
| `docs/ML_SERVICE.md` | PDF parsing, extraction, quote verification, verifier, writer, reel script, TTS, b-roll, caching |
| `docs/PROMPTS.md` | Full Gemini prompts with JSON schemas |
| `docs/GUARDRAILS.md` | Deterministic policy checks, banned terms, risk + Indian population-fit rubric |
| `docs/BRAND.md` | Steer design tokens, typography, voice rules, copy constraints |
| `docs/RENDERING.md` | Carousel/static HTML templates + Playwright, reel composition in Remotion |
| `docs/FRONTEND.md` | Next.js pages, components, review console UX |
| `docs/TESTING.md` | Unit, integration and golden tests, incl. the blocked-claim test |
| `docs/DEMO_AND_SUBMISSION.md` | Papers, Loom script, metrics to report, submission checklist |
| `docs/TASKS.md` | Single ordered build checklist |

---

## Free stack

| Layer | Tool |
|---|---|
| Core API | Java 21, Spring Boot 3, Flyway |
| ML service | Python 3.11, FastAPI, Docling, PyMuPDF, rapidfuzz, google-genai, edge-tts |
| LLM | Gemini API free tier (`gemini-2.5-flash` default, configurable) |
| LLM fallback | Groq free tier (OpenAI-compatible), optional |
| Render service | Node 20, Express, Playwright (PNG), Remotion (MP4) |
| B-roll | Pexels API (free key) |
| Music | Pixabay Music / YouTube Audio Library (downloaded, bundled) |
| DB | PostgreSQL 16 (Docker) |
| Frontend | Next.js 14 (App Router), Tailwind |
| Fonts | Google Fonts: Instrument Serif, Inter, Caveat |

---

## Quick start

```bash
cp .env.example .env          # add GEMINI_API_KEY and PEXELS_API_KEY
docker compose up -d postgres
# terminal 1
cd backend && ./mvnw spring-boot:run
# terminal 2
cd ml-service && python -m venv .venv && source .venv/bin/activate \
  && pip install -r requirements.txt && uvicorn app.main:app --port 8001 --reload
# terminal 3
cd render-service && npm i && npx playwright install chromium && npm run dev   # port 8002
# terminal 4
cd frontend && npm i && npm run dev                                           # port 3000
```

Open http://localhost:3000, upload a PDF, and follow the pipeline.

### `.env.example`

```env
# LLM
GEMINI_API_KEY=
GEMINI_MODEL_EXTRACT=gemini-2.5-flash
GEMINI_MODEL_VERIFY=gemini-2.5-flash
GEMINI_MODEL_WRITE=gemini-2.5-flash
LLM_CACHE_DIR=./.cache/llm
LLM_MAX_RPM=8                 # stay under free-tier limits
GROQ_API_KEY=                 # optional fallback

# Media
PEXELS_API_KEY=
TTS_VOICE_EN=en-IN-NeerjaNeural
TTS_VOICE_HI=hi-IN-SwaraNeural

# Services
DB_URL=jdbc:postgresql://localhost:5432/steerproof
DB_USER=steer
DB_PASSWORD=steer
ML_SERVICE_URL=http://localhost:8001
RENDER_SERVICE_URL=http://localhost:8002
STORAGE_DIR=../storage        # shared folder: pdfs, page images, renders, audio
NEXT_PUBLIC_API_URL=http://localhost:8080
```

---

## Repo layout

```
steerproof/
├── AGENTS.md  CLAUDE.md  README.md  docs/*.md
├── docker-compose.yml
├── .env.example
├── storage/                 # gitignored: papers/, pages/, renders/, audio/, broll/
├── backend/                 # Spring Boot (port 8080)
├── ml-service/              # FastAPI (port 8001)
├── render-service/          # Node: Playwright + Remotion (port 8002)
│   ├── templates/           # carousel.html, static.html
│   └── remotion/            # Reel composition
├── frontend/                # Next.js (port 3000)
└── demo/                    # seed papers, bad-claim script, metrics export
```

## Credits and disclosure

Built with Gemini API, Docling, Remotion, Playwright, edge-tts (Microsoft neural voices), and Pexels stock video. Planning was assisted by Gemini, ChatGPT, Grok, Kimi and Claude. All research papers used are open access and cited in every post.
