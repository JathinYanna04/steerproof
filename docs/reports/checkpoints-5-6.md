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
