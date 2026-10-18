# Checkpoints 5 & 6 Report: Carousel/Static Rendering & Reel Video

## PART 0: PRECHECKS

### 1. Section B Confirmation (Writer + Policy)
Queried `GET /api/papers/P-0001/assets`:
- `A-0001-CAR` (CAROUSEL): Status `RENDERED`, valid 7-slide content JSON.
- `A-0001-STA` (STATIC): Status `RENDERED`, valid headline/support content JSON.
- `A-0001-REEL` (REEL): Status `DRAFT`, valid 5-scene content JSON.
- `A-0001-CAP` (CAPTION): Status `DRAFT`, valid 4-paragraph content JSON.

### 2. Software Versions
- **node**: `v26.11.1`
- **npm**: `12.2.1`
- **playwright**: `1.64.1`
- **remotion**: `4.0.536`
- **@remotion/renderer**: `4.0.536`
- **@remotion/bundler**: `4.0.536` *(All Remotion packages verified at identical version `4.0.536`)*
- **edge-tts**: `7.3.1`
- **ffmpeg**: `n9.0.2` (built with libx264, libass, libmp3lame, libfreetype)

### 3. PEXELS_API_KEY Status
- Ran: `curl -H "Authorization: $PEXELS_API_KEY" "https://api.pexels.com/videos/search?query=chai&per_page=1"`
- Result: `401 Unauthorized` (`PEXELS_API_KEY` is not populated in `.env`).
- As specified in `docs/RENDERING.md`, when the API key is unset or a query is denylisted, the pipeline gracefully falls back to the brand procedural motion background (cream `#FBF8F3` with soft drifting blush shapes, or maroon for closing scenes).

### 4. Font Assets Downloaded (Local-Only Guarantee)
Downloaded from Google Fonts CDN into `render-service/assets/fonts/`:
- `InstrumentSerif-Regular.woff2` (21,032 bytes)
- `InstrumentSerif-Italic.woff2` (22,128 bytes)
- `Inter-Regular.woff2` (23,664 bytes)
- `Inter-Medium.woff2` (24,272 bytes)
- `Inter-SemiBold.woff2` (24,452 bytes)
- `Caveat-Medium.woff2` (50,904 bytes)

### 5. Royalty-Free Music Downloaded
- **Track**: Calm Ambient (ID: 111899)
- **File**: `render-service/assets/music/calm-1.mp3` (4.5 MB, 147.2s duration, 256 kbps MP3)
- **Attribution & Source**: Documented in `demo/CREDITS.md`.

---

## PART 1: RENDER-SERVICE SKELETON

### 1. Service Layout
Built with Node 20 (`v26.11.1`), TypeScript, ESM, Express on port 8002:
- `render-service/src/brand.ts`: Single source of truth for Steer colour tokens (`cream: #F5EBDF`, `rust: #C04E2A`, `maroon: #661414`, `ink: #351D1D`, `clay: #B58777`, `blush: #E6CBBD`, `softPaper: #F8F2EB`).
- `render-service/src/escape.ts`: Implemented `escapeHtml` (encodes `&`, `<`, `>`, `"`, `'`) and `toHeadline` (safely translates `*word*` to `<em class="accent">word</em>`).
- `render-service/src/html.ts`: Generates semantic HTML for slides and statics, injecting CSS variables from `brand.ts` and inlining base64 fonts for 100% offline local rendering.
- `render-service/src/shoot.ts`: Playwright singleton browser runner with font verification, automated text overflow step-down, block bounding rect extraction, and full-resolution screenshot capture.
- `render-service/src/server.ts`: Express API exposing `POST /carousel`, `POST /static`, `POST /reel`, `GET /health`, static `/files` from `STORAGE_DIR`, and static `/assets` from `assets/`.

### 2. Zero Hex Literal Rule (`npm run lint:colors`)
Added lint script to `render-service/package.json` that audits all code in `templates/`, `src/`, and `remotion/` for any hex colors (`#[0-9a-fA-F]{3,6}`) outside `brand.ts`:
```
$ npm run lint:colors
lint:colors passed! No hex literals found outside brand.ts.
```
Result: 0 hex literals found outside `brand.ts`.

