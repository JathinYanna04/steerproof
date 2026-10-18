# SteerProof – System Startup & Operation Guide

SteerProof is a dual-gated clinical verification and multi-format content generation suite designed for metabolic health communications.

```
                    ┌─────────────────────────┐
                    │  Frontend (Next.js 14)  │
                    │       Port 3000         │
                    └────────────┬────────────┘
                                 │ REST API
                                 ▼
                    ┌─────────────────────────┐
                    │ Backend (Spring Boot 3) │
                    │       Port 8080         │
                    └──────┬────────────┬─────┘
          Internal REST    │            │ Internal REST
                 ┌─────────┘            └──────────┐
                 ▼                                 ▼
   ┌───────────────────────────┐    ┌─────────────────────────────┐
   │ ML Service (Python/FastAPI)│    │ Render Service (Node/Remot.)│
   │        Port 8001          │    │         Port 8002           │
   └─────────────┬─────────────┘    └──────────────┬──────────────┘
                 │                                 │
                 ▼                                 ▼
       ┌──────────────────┐               ┌──────────────────┐
       │   PostgreSQL 16  │               │ Storage / Renders│
       │     Port 5432    │               │  `./storage/`    │
       └──────────────────┘               └──────────────────┘
```

---

## Service Map & Port Reference

| Service | Port | Tech Stack | Responsibilities |
| :--- | :--- | :--- | :--- |
| **PostgreSQL** | `5432` | Postgres 16 (Docker) | Relational persistence, audit trail, claims, assets, human review events |
| **ML Service** | `8001` | Python 3.12, FastAPI, Docling, google-genai, Edge-TTS | PDF parsing, claim extraction, entailment & risk verification, content drafting, voiceover synthesis |
| **Render Service** | `8002` | Node.js 20+, Express, Playwright, Remotion | 4:5 vertical carousels, static multi-ratio cards, 9:16 vertical MP4 video reels |
| **Backend** | `8080` | Java 21+, Spring Boot 3.3.4, Flyway, JPA | Pipeline orchestrator, Gate 1 & 2 enforcement, policy checks, ZIP export packaging, static file server |
| **Frontend** | `3000` | Next.js 14 App Router, React 18, Tailwind CSS | Clinician review console, click-to-source bbox overlays, asset previewers, export console |

---

## Prerequisites

Ensure the following tools and runtimes are installed on your Linux / Unix host:

- **Docker** (for PostgreSQL)
- **Python 3.12+** with `venv`
- **Node.js 20+** and `npm`
- **JDK 21+** (Java 27 supported via Maven wrapper)
- **System packages**: `chromium`, `ffmpeg`, `libasound2`

---

## Environment Variables (`.env`)

A root `.env` file configures all services:

```bash
# LLM & AI Keys
GEMINI_API_KEY=your_gemini_api_key_here
GEMINI_MODEL=gemini-2.5-flash

# Stock Video Footage (Optional: falls back gracefully to local broll library and procedural motion backgrounds)
PIXABAY_API_KEY=your_pixabay_api_key_here

# Operational Modes
DEMO_MODE=true
STORAGE_DIR=/home/yjr/steerproof/storage

# Database Credentials
DB_URL=jdbc:postgresql://localhost:5432/steerproof
DB_USER=steer
DB_PASSWORD=steer
```

---

## Startup Sequence (In Exact Order)

### Step 0: Start PostgreSQL Container
```bash
# If container steerproof-postgres already exists:
docker start steerproof-postgres

# Or launch a new PostgreSQL 16 instance:
docker run -d \
  --name steerproof-postgres \
  -e POSTGRES_DB=steerproof \
  -e POSTGRES_USER=steer \
  -e POSTGRES_PASSWORD=steer \
  -p 5432:5432 \
  postgres:16-alpine
```

### Step 1: Start ML Service (Port 8001)
```bash
cd /home/yjr/steerproof/ml-service
PYTHONPATH=. .venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8001
```
*Healthcheck:* `curl http://localhost:8001/health`

### Step 2: Start Render Service (Port 8002)
```bash
cd /home/yjr/steerproof/render-service
npm run start
```
*Healthcheck:* `curl http://localhost:8002/health`

### Step 3: Start Backend API (Port 8080)
```bash
cd /home/yjr/steerproof/backend
java -jar target/steerproof-backend-0.0.1-SNAPSHOT.jar
# Or in development mode:
# ./mvnw spring-boot:run
```
*Healthcheck:* `curl http://localhost:8080/actuator/health`

### Step 4: Start Frontend Console (Port 3000)
```bash
cd /home/yjr/steerproof/frontend
npm run start
# Or in development mode:
# npm run dev
```
*UI Entrypoint:* [http://localhost:3000](http://localhost:3000)

---

## Demo Mode (`DEMO_MODE=true`)

When `DEMO_MODE=true` is set:
1. **Live Adversarial Seeding**:
   ```bash
   # Injects an unscientific overclaim into P-0002 and runs it through real Gemini verifier:
   curl -X POST http://localhost:8080/api/demo/seed-bad-claim/P-0002
   ```
2. **Review State Reset**:
   ```bash
   # Clears human reviews, resets claims to PENDING and assets to RENDERED:
   curl -X POST "http://localhost:8080/api/papers/P-0001/reset?keepClaims=true"
   ```

---

## Automated Test Suites

### Backend Tests (34 unit & integration tests)
```bash
cd /home/yjr/steerproof/backend
./mvnw test
```
*Includes `Gate2AndExportIntegrationTest`, `PolicyCheckerTest`, and `RetryIdempotencyTest`.*

### ML Service Tests (23 pytest tests)
```bash
cd /home/yjr/steerproof/ml-service
PYTHONPATH=. .venv/bin/pytest
```
*Includes number check range normalizers, quote Relinkers, risk floor invariants, and writer schema validators.*

### Playwright UI Smoke Test & Screenshot Capture
```bash
cd /home/yjr/steerproof
node scripts/ui_smoke_test.js
```
*Generates full-page screenshots in `storage/debug/`: `ui-home.png`, `ui-pipeline.png`, `ui-claims.png`, `ui-assets.png`, and `ui-export.png`.*

---

## Clinician Review Workflow (How to Review & Export)

1. Open **[http://localhost:3000](http://localhost:3000)** in your browser.
2. Confirm the reviewer identity in the top-right header (defaults to `Jathin Reddy Yanna (non-clinician reviewer)`).
3. Select paper **`P-0001`** (Hall & Kahan 2018) or **`P-0002`** (Wilding et al. 2021).
4. Click **Gate 1: Claims Console**:
   - Inspect scientific claims vs direct quotes and passage bounding-box overlays on the real PDF pages.
   - Use shortcuts (`j`/`k` for next/prev, `a` to approve, `e` to edit, `r` to reject).
   - Once all claims are reviewed, click **"Generate Content"**.
5. Click **Gate 2: Asset Review**:
   - Verify Carousel slides, Static images, 9:16 Video Reel with audio, and Instagram Caption.
   - Click on any block or scene to verify its linked study quote in the Source Evidence Inspector drawer.
   - Review policy compliance. Approve each asset.
6. Click **Export Release Package**:
   - If any asset is unapproved, review the blocked checklist.
   - Once all 4 formats are approved, click **"Export Approved Content"** to generate the digitally signed `P-xxxx.zip` package!
