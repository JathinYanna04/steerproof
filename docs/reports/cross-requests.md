
## From WS4 (b-roll) → lead / WS5 (backend) / WS1 (writer) / schemas owner

### WS4-1 (BLOCKER, user action): Pixabay key is empty
`.env` contains `PIXABAY_API_KEY=` with an empty value (verified via settings, value never printed). There is no
ml-service/.env and the key isn't in the shell env. Until the user pastes a key, every scene resolves to
`null: NO_API_KEY` and reels use the procedural background. After filling it in: restart ml-service (log line
`pixabay key loaded: yes`), then run
`cd ml-service && PYTHONPATH=. .venv/bin/python -m app.reel.broll_evidence --out ../docs/reports/ws4-broll-evidence.md --frames ../storage/debug/broll-frames P-0001 P-0002`
and look at every frame in storage/debug/broll-frames/ for people's bodies.

### WS4-2 (backend, PipelineService reel render): the backend never calls /reel/broll (or /reel/audio)
`PipelineService` (REEL branch near line 885, `renderServiceClient.renderReel(paperId, scenesList, citation, draft)`)
sends the writer scenes straight to render-service. B-roll and voiceover were only ever added by the out-of-band
`scripts/rerender_reels_pixabay.py`. Requested change, before `renderReel`:
1. POST `{ML}/reel/audio` with `{assetId, scenes, language, voice}` (as the script does) and merge
   `audioSrc = "audio/{assetId}/scene-{n}.mp3"`, `durationMs`, `words` into each scene.
2. POST `{ML}/reel/broll` with
   `{"assetId": "...", "paperId": "...", "scenes": [{"n": n, "brollQuery": q, "minSeconds": durationMs/1000}]}`
   (all scenes of the reel in ONE call; no `apiKey` field, so ml-service uses its own key), then merge per scene `n`:
   `videoPath, source, url, creator, userId, creditLine, tags, sizeUsed, hitId, reason` into the scene map
   (render-service already turns `videoPath` into `brollSrc`). Persist them in the asset content so
   ExportController's sources.md credits and the UI read real data. `reason` is null on success, else one of
   `NO_HITS | ALL_DENYLISTED | ERROR:<msg> | NO_API_KEY | DENYLIST:<term> | EMPTY_QUERY`.
3. If /reel/broll fails, render without b-roll and store the failure text in each scene's `reason`. Never fabricate a credit.

### WS4-3 (ml-service/app/schemas.py, small additive change)
`process_reel_broll` now accepts `used_ids` and returns `searchedQuery`, `width`, `height`. FastAPI's response_model
drops fields the schema doesn't list. Please add:
- `ReelBrollRequest.usedIds: Optional[list[str]] = None`
- `ReelBrollResult.searchedQuery: Optional[str] = None`, `width: Optional[int] = None`, `height: Optional[int] = None`
and in main.py `/reel/broll`: `process_reel_broll(..., api_key=req.apiKey, used_ids=req.usedIds)`.
(The legacy `pexelsId/pexelsUrl/photographer*` result fields are no longer populated. ExportController already reads
`creator`/`url` first, so nothing breaks.)

### WS4-4 (WS1, writer prompt): brollQuery guidance
Add to ml-service/app/prompts/writer.md for each reel scene's `brollQuery`:
"brollQuery: 2–3 plain words naming concrete objects, food or places only, the kind of thing a stock-video site
has. Good: "chai cup", "roti tawa", "vegetable market", "office desk laptop", "morning park path",
"kitchen counter", "wall clock". Never people, bodies, faces, hands, doctors, clinics, hospitals, stethoscopes,
pills, injections, scales, measuring tapes, gyms, food being eaten by someone, or before/after imagery.
Use a different query for each scene."
(The current reels use queries like "stethoscope medical clipboard", "medical journal pages open" and "bright clean
clinic room", which tend to return clinical or people footage.)

### WS4-5 (lead, FYI): ml-service/app/config.py defaults name "gemini-3.7-flash"
`Settings.gemini_model*` default to `"gemini-3.7-flash"`. `.env` sets the GEMINI_MODEL_* values (names verified,
values not read). The submission's tool list must use the model the running service reports (`GET :8001/health`),
not these defaults. I didn't change them because they're outside WS4.

## From WS2 (frontend) → lead / WS5 (backend)

### WS2-1 (backend, asset content): persist per-scene timing on REEL scenes
The reel tab syncs the scene list to `video.currentTime`. It reads, in order: `scene.startSec/endSec`, then
`scene.durationMs` (it applies Remotion's `ceil((durationMs+200)/1000*30)` frames per scene), then the duration of
`storage/audio/{assetId}/scene-{n}.mp3`, then an even split. If WS4-2 lands (`durationMs` merged into each scene and
persisted in the asset content), the UI uses it automatically. If render-service returns exact scene start/end
seconds, storing them as `startSec`/`endSec` on each scene is the most precise option.

### WS2-2 (backend, FYI): CORS allows only http://localhost:3000
`WebConfig` rejects other origins, so a frontend on another port can't call the API. That's fine for the demo
(`npm run start` on :3000). The smoke test has `SMOKE_DISABLE_CORS=1` only for private-port runs.

### WS2-3 (lead, for the final run): rebuild and restart :3000
The running :3000 server is `npm run start` serving an old `.next` build. After merging, run
`cd frontend && npm run build && npm run start` (restart the :3000 process) and then
`node scripts/ui_smoke_test.js` (defaults: BASE_URL=http://localhost:3000, API_URL=http://localhost:8080, PAPER_ID=P-0001).

## From WS5 (data + pipeline), for the lead
- Restart the :8080 backend from current code. V2 (pipeline_runs.model) is already applied to the dev DB. The old jar still runs fine on it, but it lacks /runs, the new stats and metrics.csv.
- Restart ml-service so /parse uses the new meta.py. It does Crossref and PubMed lookups, cached under storage/cache/meta, and no longer hardcodes Hall & Kahan.
- Historic pipeline_runs have model=null because they predate the column. Only new runs record it.
- pipelineSeconds for P-0001/P-0002 is wall clock and includes today's Gemini 503/429 failures and retry gaps (7844 s and 5833 s). pipelineActiveSeconds (247 s and 560 s) is the stage-only time. A fresh upload gives a clean single-run number for the submission table.
- ExportController's regenerateDemoCredits() hardcodes hex colours in the CREDITS text (#FBF8F3, #E6CBBD, #3D141A). #3D141A is not the brand maroon (#661414). It also hardcodes the music credit text. Not changed by WS5.
- Run backend tests from an isolated copy or when no other agent is compiling. A shared target/ produced NoClassDefFoundError races during this sprint.

## From WS3 (render polish)

1. **Backend reel render payload (lead, `PipelineService` RENDER + `reRenderAsset` REEL branch, ~line 885).** Today the backend posts the stored scenes as-is to `POST /reel`: no `audioSrc`, `durationMs`, `words` or `brollSrc`. Only `scripts/rerender_reels_pixabay.py` calls ml `/reel/audio` + `/reel/broll` first. A backend-triggered reel render therefore has no voice or subtitles, and every scene falls back to 4 s (the renderer now defaults missing `durationMs` to 4000 ms instead of NaN). The backend should call `/reel/broll` + `/reel/audio` and pass, per scene: `n, onScreen, motion, kind, claimIds, voiceover, audioSrc, durationMs, words, brollSrc (or videoPath), citation (claim scenes only), statEligible, stat`.
2. **Fields the renderer consumes (WS1).**
   - Per slide, scene and static: `kind`, `claimIds`, optional `citation`, `statEligible`, `stat`.
   - The request-level `citation` (already built by `CitationHelper` from the paper row) is used when a block has no own `citation`, and only on kind=claim blocks and the source slide.
   - The static's source line needs `kind:"claim"` (or non-empty `claimIds`) on the static content; `footnote` is ignored.
   - The big number needs `statEligible:true`; `stat` (e.g. `"100 kcal"`) is preferred, otherwise the first number in the body is used.
   - `quote` motion falls back to `title` unless kind=claim. A `list` with fewer than 2 items (split on ` · `, `;` or newlines) falls back to `title`.
3. **Block IDs changed (WS2).**
   - The closing slide no longer has a `slideN.footer` block.
   - The source slide's `slideN.body` exists only when a non-citation caveat remains.
   - `static.footnote` exists only on claim statics.
   - Citation lines have no `data-block-id`, so they are not clickable.
