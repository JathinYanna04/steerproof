# Architecture

## Services

```
┌──────────────┐  REST   ┌────────────────────┐  REST   ┌───────────────────────┐
│ frontend     │◄───────►│ backend (Spring)   │◄───────►│ ml-service (FastAPI)  │
│ Next.js :3000│         │ :8080              │         │ :8001                 │
│ upload,      │         │ orchestration,     │         │ docling, gemini,      │
│ review,      │         │ state machines,    │         │ quote check, writer,  │
│ previews     │         │ gates, policy,     │         │ edge-tts, pexels      │
└──────────────┘         │ audit, export      │         └───────────────────────┘
                         │                    │  REST   ┌───────────────────────┐
                         │                    │◄───────►│ render-service (Node) │
                         └─────────┬──────────┘         │ :8002 playwright,     │
                                   │                    │ remotion              │
                            ┌──────▼──────┐             └───────────────────────┘
                            │ PostgreSQL  │     shared folder: STORAGE_DIR
                            └─────────────┘
```

- **backend** is the only service that touches the database. It owns all state and calls the other two services.
- **ml-service** and **render-service** are stateless. They read and write files in `STORAGE_DIR` and return JSON.
- All four run locally. Postgres runs in Docker.

## Pipeline

```
UPLOAD ─► PARSE ─► EXTRACT ─► VERIFY ─► [GATE 1: doctor reviews claims]
   ─► WRITE ─► POLICY ─► RENDER ─► [GATE 2: doctor reviews assets] ─► EXPORT
```

| Stage | Service | Input | Output |
|---|---|---|---|
| UPLOAD | backend | PDF / URL | `papers` row, file at `storage/papers/P-xxxx.pdf` |
| PARSE | ml `/parse` | pdf path | passages[] + page PNGs at `storage/pages/P-xxxx/{n}.png` |
| EXTRACT | ml `/extract` | passages | candidate claims citing passage_id |
| VERIFY | ml `/verify` | claims + passages | quote_score, numbers_ok, entailment, risk, population_fit |
| GATE 1 | frontend + backend | claims | approved claims |
| WRITE | ml `/write` | approved claims + theme | carousel/static/reel/caption JSON with claimIds |
| POLICY | backend `PolicyChecker` | all copy strings | violations |
| RENDER | ml `/reel/audio`, ml `/reel/broll`, render `/carousel`, `/static`, `/reel` | asset JSON | PNGs, MP4 |
| GATE 2 | frontend + backend | assets | approved assets |
| EXPORT | backend | approved | zip |

The backend runs stages asynchronously with `@Async` + a `TaskExecutor` (pool size 2). Each stage updates `papers.stage` and `pipeline_runs`. The frontend polls `GET /api/papers/{id}`.

## State machines

### Paper (`papers.status`)
```
PARSING → EXTRACTING → VERIFYING → AWAITING_CLAIM_REVIEW
  → WRITING → RENDERING → AWAITING_ASSET_REVIEW → READY_TO_EXPORT → EXPORTED
any stage → FAILED (with stage + error). POST /retry resumes from the failed stage.
```

### Claim (`claims.doctor_status`)
```
PENDING → APPROVED | EDITED | REJECTED
NEEDS_EVIDENCE → EDITED (re-linked to a passage, re-verified) | REJECTED
APPROVED/EDITED → REJECTED or EDITED again (after render: marks dependent assets STALE)
```

### Asset (`assets.status`)
```
DRAFT → RENDERED → APPROVED
RENDERED → BLOCKED (policy violation or a non-approved claim) → regenerate → RENDERED
APPROVED/RENDERED → STALE (a linked claim changed) → regenerate → RENDERED
```

### Gate rules (enforced in `ReviewService`)
- `canWrite(paper)`: no claim `PENDING` or `NEEDS_EVIDENCE` AND ≥3 claims `APPROVED|EDITED`.
- `canApproveAsset(asset)`: status `RENDERED` AND every linked claim `APPROVED|EDITED` AND no `BLOCKING` violation.
- `canExport(paper)`: every asset `APPROVED`. Otherwise 422 with `blockedBy`.

## docker-compose.yml

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: steerproof
      POSTGRES_USER: steer
      POSTGRES_PASSWORD: steer
    ports: ["5432:5432"]
    volumes: [pgdata:/var/lib/postgresql/data]
volumes:
  pgdata:
```

The other services run natively during development. Containerizing them is optional.

## Concurrency and limits
- The ml-service has a token-bucket rate limiter around every Gemini call (`LLM_MAX_RPM`).
- On HTTP 429 from Gemini: exponential backoff 5 s / 15 s / 45 s, then fail the stage. If `GROQ_API_KEY` is set, switch to the fallback provider for that call and log it.
- Remotion renders run one at a time (a queue inside render-service).

## Storage layout
```
storage/
├── papers/P-0001.pdf
├── pages/P-0001/1.png ...        # 2x resolution page renders
├── parsed/P-0001.json            # docling passages cache
├── audio/A-0001-REEL/scene-1.mp3 + scene-1.words.json
├── broll/<pexels-id>.mp4
├── renders/P-0001/carousel/1.png ...  static.png  reel.mp4
└── exports/P-0001.zip
```
