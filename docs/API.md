# API

Base URL: `http://localhost:8080`. JSON everywhere. Errors use the shape `{ "error": "CODE", "message": "...", "details": {...} }`.

## Public API (Spring backend)

### Papers
```
POST /api/papers                         multipart: file (pdf) | form: url ; optional: theme, batchId
→ 201 { "paperId":"P-0001", "status":"PARSING" }

POST /api/papers/batch                   multipart: files[] (≤5)
→ 201 { "batchId":"B-0001", "paperIds":[...] }

GET  /api/papers                         → [{ paperId, title, authors, year, journal, doi, pdfPath, status, createdAt, stats }]  (newest first)
GET  /api/papers/{id}
→ { paperId, title, authors, year, journal, doi, status, failedStage, error, createdAt,
    stages: <same array as /runs>,
    stats:{ claimsTotal, claimsAutoFlagged, approved, edited, rejected, pending, needsEvidence,
            reviewSecondsTotal, humanSecondsTotal, pipelineSeconds, pipelineActiveSeconds, writeRenderSeconds } }
GET  /api/papers/{id}/runs               latest run of each stage, always 5 entries in order PARSE, EXTRACT, VERIFY, WRITE, RENDER
→ [{ stage, status: DONE|RUNNING|FAILED|WAITING, startedAt, endedAt, seconds, model, llmCalls, cacheHits, error }]
   model: the Gemini model the ml-service reports on /health for LLM stages, "docling" for PARSE,
   "playwright + remotion, edge-tts" for RENDER; null for runs recorded before V2.
   Stats: claimsAutoFlagged = risk AMBER/RED or NEEDS_EVIDENCE as set by the pipeline; humanSecondsTotal = sum of human_events;
   pipelineSeconds = wall clock from the latest successful PARSE (started before the latest successful VERIFY) to that VERIFY's end,
   including retry gaps; pipelineActiveSeconds = summed durations of that chain's PARSE, EXTRACT and VERIFY runs;
   writeRenderSeconds = latest successful WRITE start → end of the last successful RENDER after it. Unknown values are null.

POST /api/papers/{id}/retry              → 202   (resumes from failedStage)
GET  /api/papers/{id}/pdf                → application/pdf
GET  /api/papers/{id}/pages/{n}.png      → image/png
```

### Passages and claims
```
GET  /api/papers/{id}/passages?page=3    → [{ passageId, section, kind, page, bbox, pageWidth, pageHeight, text }]
GET  /api/papers/{id}/claims             → [Claim]
GET  /api/claims/{claimId}               → Claim (with passage embedded)

PATCH /api/claims/{claimId}
  { "action":"APPROVE"|"EDIT"|"REJECT",
    "doctorName":"Dr. A",
    "editedCopy"?: "...",          // EDIT only
    "passageId"?: "E-0001-0042",   // EDIT may re-link evidence; triggers re-verify
    "note"?: "...",                // required for REJECT
    "reviewSeconds": 41 }
→ 200 { claim, staleAssets:[assetIds] }
→ 409 if action not allowed from current status (e.g. APPROVE on NEEDS_EVIDENCE)

POST /api/papers/{id}/generate           // Gate 1 → WRITE → POLICY → RENDER
→ 202 | 422 { "error":"GATE1_BLOCKED", "details":{ "pending":[...], "needsEvidence":[...], "approvedCount":2 } }
```

`Claim` shape:
```json
{
  "claimId":"C-0001-007", "paperId":"P-0001",
  "scientificClaim":"...", "consumerDraft":"...", "quote":"...",
  "numbers":[{"value":"10.0","unit":"%","context":"..."}],
  "studyDesign":"randomized controlled trial extension",
  "population":"adults with overweight/obesity, STEP 1 sub-population",
  "findingClass":"EXPLORATORY",
  "limitations":["..."],
  "quoteScore":97, "numbersOk":true,
  "entailment":"SUPPORTED",
  "risk":"AMBER", "riskReason":"exploratory analysis; must be qualified",
  "populationFit":"MEDIUM", "populationReason":"few South Asian participants",
  "doctorStatus":"PENDING", "editedCopy":null,
  "passage":{ "passageId":"E-0001-0042", "page":3, "bbox":{"l":..,"t":..,"r":..,"b":..}, "pageWidth":612, "pageHeight":792, "section":"Results" },
  "usedIn":[{"assetId":"A-0001-CAR","location":"slide:3"}]
}
```

### Assets
```
GET  /api/papers/{id}/assets
→ [{ assetId, type, language, status, version, content, renderUrls:[...],
     violations:[{rule,severity,detail,location}], claimIds:[...] }]

POST /api/assets/{assetId}/regenerate    → 202  (re-write + policy + render for this asset only)
POST /api/assets/{assetId}/approve       { "doctorName":"...", "reviewSeconds":30 }
→ 200 | 422 { "error":"ASSET_BLOCKED", "details":{ "claims":[...], "violations":[...], "status":"STALE" } }
PATCH /api/assets/{assetId}/schedule     { "date":"2026-10-26" }
GET  /files/**                           → static files from STORAGE_DIR (renders, pages)
```

### Export and metrics
```
POST /api/papers/{id}/export
→ 200 { "zipUrl":"/files/exports/P-0001.zip" }
→ 422 { "error":"EXPORT_BLOCKED", "details":{ "blockedBy":[{ "assetId":"A-0001-REEL", "reason":"STALE" }, { "claimId":"C-0001-009", "reason":"REJECTED but used in scene:3" }] } }

GET /api/metrics.csv                     → text/csv, one row per paper: paperId,title,status + every stats field above (empty = unknown)
GET /api/batches/{id}/week               → [{ date, assetId, type, paperId, status }]
GET /api/batches/{id}/calendar.ics
POST /api/papers/{id}/hinglish           → 202 (bonus; requires all English assets APPROVED)

POST /api/papers/{id}/human-events       { "actor":"...", "kind":"UPLOAD", "seconds":20 }
GET  /api/audit?entityId=C-0001-007
```

### Export zip contents
```
P-0001/
├── carousel/1.png … n.png
├── static.png
├── reel.mp4
├── caption.txt              # caption + hashtags, ready to paste
├── sources.md               # every claim → quote → page → paper citation
├── review_report.json       # approvals, doctor names, timestamps, versions
└── README.txt               # "Approved for publishing by <doctor> on <date>"
```

## Internal: ml-service (FastAPI :8001)

All requests take `paperId`. Paths are relative to `STORAGE_DIR`.

```
POST /parse      { paperId, pdfPath }
→ { meta:{title,authors,year,journal,doi}, pageCount, passages:[Passage], pageImages:["pages/P-0001/1.png", ...] }

POST /fetch-url  { paperId, url } → { pdfPath }      # downloads a PDF; for PMC pages finds the PDF link

POST /extract    { paperId, meta, passages:[Passage], theme? }
→ { claims:[{ passageId, quote, scientificClaim, consumerDraft, numbers, studyDesign, population, findingClass, limitations }],
    llmCalls, cacheHits }

POST /verify     { paperId, claims:[...with passageId], passages:[Passage] }
→ { results:[{ index, quoteScore, numbersOk, missingNumbers:[], entailment, risk, riskReason, populationFit, populationReason }], llmCalls, cacheHits }

POST /write      { paperId, meta, claims:[ApprovedClaim], theme?, language:"en"|"hinglish", only?: "CAROUSEL"|"STATIC"|"REEL"|"CAPTION" }
→ { carousel:{...}, static:{...}, reel:{...}, caption:{...}, llmCalls, cacheHits }   # shapes in docs/PROMPTS.md

POST /reel/audio { assetId, scenes:[{ n, voiceover }], language }
→ { scenes:[{ n, audioPath, durationMs, words:[{ text, startMs, endMs }] }] }

POST /reel/broll { assetId, scenes:[{ n, brollQuery }] }
→ { scenes:[{ n, videoPath, pexelsId, pexelsUrl, photographer }] }   # null videoPath if none found → render uses a plain motion background

GET  /health
```

## Internal: render-service (Node :8002)
```
POST /carousel { assetId, paperId, slides:[Slide], citation }   → { paths:["renders/P-0001/carousel/1.png", ...], blocks:[{slide, blockId, x,y,w,h}] }
POST /static   { assetId, paperId, static:Static, citation, sizes:["1080x1350","1080x1080","1080x1920"] } → { paths:[...], blocks:[...] }
POST /reel     { assetId, paperId, reel:Reel, audio:[...], broll:[...], citation } → { path:"renders/P-0001/reel.mp4", durationMs }
GET  /health
```

`blocks` returns the pixel rectangles of every text block with its `blockId`. The frontend overlays clickable regions on previews, and `blockId` maps to `claimIds` through the asset content.

## Demo endpoints (`DEMO_MODE=true` only, 403 otherwise)

| Method | Path | Does |
|---|---|---|
| POST | `/api/demo/seed-bad-claim/{paperId}` | Inserts `C-xxxx-BAD` ("Weight always comes back fully when you stop treatment.") on a passage of the paper and runs it through the real verifier. Returns the claim with entailment, risk and reasons. Audited as `BAD_CLAIM_SEEDED` |
| DELETE | `/api/demo/seed-bad-claim/{paperId}` | Removes the paper's seeded claims so a real review starts clean. 409 `CLAIM_IN_USE` while a generated asset still links one. Audited as `BAD_CLAIM_REMOVED` |

