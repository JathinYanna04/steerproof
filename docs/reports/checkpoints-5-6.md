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

---

## PART 2: CAROUSEL + STATIC TEMPLATES

### 1. Rendered Asset Paths
- Carousel 1080×1350 PNGs:
  - `storage/renders/P-0001/carousel/1.png` (Hook)
  - `storage/renders/P-0001/carousel/2.png` (Point - 100 kcal stat)
  - `storage/renders/P-0001/carousel/3.png` (Illustration - softPaper card)
  - `storage/renders/P-0001/carousel/4.png` (Point - 29 studies stat)
  - `storage/renders/P-0001/carousel/5.png` (Point - Support)
  - `storage/renders/P-0001/carousel/6.png` (Source - Literature citation)
  - `storage/renders/P-0001/carousel/7.png` (Closing - Maroon `#661414` full-bleed)
- Static PNGs:
  - `storage/renders/P-0001/static.png` (Default portrait 1080×1350)
  - `storage/renders/P-0001/static-1080x1080.png` (Bonus square 1080×1080)
  - `storage/renders/P-0001/static-1080x1920.png` (Bonus story 1080×1920)
- Contact sheet:
  - `storage/debug/P-0001-contact-sheet.png` (2160×1350 8-tile contact sheet generated via ffmpeg)

### 2. Slide 1 and Slide 3 Blocks JSON

#### Slide 1 Blocks:
```json
[
  {
    "image": "carousel/1.png",
    "blockId": "slide1.label",
    "x": 96,
    "y": 120,
    "w": 888,
    "h": 29,
    "slide": 1
  },
  {
    "image": "carousel/1.png",
    "blockId": "slide1.headline",
    "x": 96,
    "y": 513,
    "w": 888,
    "h": 202,
    "slide": 1
  },
  {
    "image": "carousel/1.png",
    "blockId": "slide1.footer",
    "x": 96,
    "y": 1170,
    "w": 888,
    "h": 40,
    "slide": 1
  }
]
```

#### Slide 3 Blocks (Illustration):
```json
[
  {
    "image": "carousel/3.png",
    "blockId": "slide3.label",
    "x": 96,
    "y": 120,
    "w": 888,
    "h": 29,
    "slide": 3
  },
  {
    "image": "carousel/3.png",
    "blockId": "slide3.headline",
    "x": 153,
    "y": 544,
    "w": 774,
    "h": 88,
    "slide": 3
  },
  {
    "image": "carousel/3.png",
    "blockId": "slide3.body",
    "x": 153,
    "y": 661,
    "w": 774,
    "h": 151,
    "slide": 3
  },
  {
    "image": "carousel/3.png",
    "blockId": "slide3.footer",
    "x": 96,
    "y": 1170,
    "w": 888,
    "h": 40,
    "slide": 3
  }
]
```

### 3. Font Verification
In `shoot.ts`, Playwright verifies `document.fonts.check('96px "Instrument Serif"') === true` after `document.fonts.ready`. Inlined base64 fonts ensure 100% offline availability without network calls.

### 4. Forced Overflow Test (`TEXT_OVERFLOW`)
Ran forced overflow curl test with a 300-character body paragraph:
```
POST /carousel
Body: { "paperId": "P-0001-overflow-test", "slides": [{ "n": 1, "body": "...300 chars..." }] }

HTTP/1.1 422 Unprocessable Entity
{
  "error": "TEXT_OVERFLOW",
  "blockId": "slide1.body",
  "message": "TEXT_OVERFLOW on slide1.body"
}
```
Result: Successfully triggered text overflow step-down loop, hit the 30px body floor, and returned `422 Unprocessable Entity` with `TEXT_OVERFLOW`.

---

## PART 3: REEL INPUTS IN ml-service (audio + b-roll)

### 1. P-0002 Reel Audio Synthesis (`POST /reel/audio`)
Synthesized using `edge-tts` (voice `en-IN-NeerjaNeural`, rate `-5%`, boundary `WordBoundary`), cached by `sha256(voice + rate + text)`, duration measured via `mutagen.mp3`:

- **Scene 1**:
  - Voiceover: *"The biggest question people ask doctors before starting metabolic care is whether the weight returns once treatment ends."*
  - MP3 Path: `storage/audio/A-0002-REEL/scene-1.mp3`
  - Duration: 8,664 ms
  - Word count: 18 words
  - First 5 word timings:
    1. "The": 100 ms – 297 ms
    2. "biggest": 311 ms – 758 ms
    3. "question": 771 ms – 1,232 ms
    4. "people": 1,245 ms – 1,626 ms
    5. "ask": 1,639 ms – 1,955 ms

- **Scene 2**:
  - Voiceover: *"When weight is lost, the body triggers compensatory biological changes that often make weight regain common."*
  - MP3 Path: `storage/audio/A-0002-REEL/scene-2.mp3`
  - Duration: 8,184 ms
  - Word count: 16 words
  - First 5 word timings:
    1. "When": 100 ms – 416 ms
    2. "weight": 429 ms – 692 ms
    3. "is": 705 ms – 863 ms
    4. "lost": 876 ms – 1,376 ms
    5. "the": 1,508 ms – 1,653 ms

- **Scene 3**:
  - Voiceover: *"In one exploratory trial, participants regained most of their lost weight over one year after stopping treatment."*
  - MP3 Path: `storage/audio/A-0002-REEL/scene-3.mp3`
  - Duration: 8,472 ms
  - Word count: 17 words
  - First 5 word timings:
    1. "In": 100 ms – 350 ms
    2. "one": 363 ms – 561 ms
    3. "exploratory": 574 ms – 1,376 ms
    4. "trial": 1,389 ms – 1,916 ms
    5. "participants": 2,061 ms – 2,850 ms

- **Scene 4**:
  - Voiceover: *"Yet, trial data suggested that nearly half the participants still maintained at least 5% weight loss one year later."*
  - MP3 Path: `storage/audio/A-0002-REEL/scene-4.mp3`
  - Duration: 9,432 ms
  - Word count: 19 words
  - First 5 word timings:
    1. "Yet": 100 ms – 653 ms
    2. "trial": 797 ms – 1,192 ms
    3. "data": 1,205 ms – 1,587 ms
    4. "suggested": 1,600 ms – 2,311 ms
    5. "that": 2,324 ms – 2,587 ms

- **Scene 5**:
  - Voiceover: *"Care must include a planned exit for real life. Clinical weight loss, doctor-led. steer · steerclub.com"*
  - MP3 Path: `storage/audio/A-0002-REEL/scene-5.mp3`
  - Duration: 10,344 ms
  - Word count: 16 words
  - First 5 word timings:
    1. "Care": 100 ms – 403 ms
    2. "must": 416 ms – 692 ms
    3. "include": 705 ms – 1,139 ms
    4. "a": 1,153 ms – 1,218 ms
    5. "planned": 1,232 ms – 1,692 ms

- **Overall Reel Audio**:
  - Combined path: `storage/audio/A-0002-REEL/reel.mp3`
  - Total duration: 45,096 ms (45.10 seconds)
  - `timingApproximate`: `false` (exact Azure neural word boundary timestamps across all 5 scenes).

### 2. Pexels B-Roll Query Processing (`POST /reel/broll`)
- **Scene 1 Query**: `"steaming chai cup desk"` -> Status: `PEXELS_API_KEY_UNSET` -> `videoPath: null` (procedural cream background fallback).
- **Scene 2 Query**: `"stethoscope medical clipboard"` -> Status: `PEXELS_API_KEY_UNSET` -> `videoPath: null`.
- **Scene 3 Query**: `"calendar turning pages"` -> Status: `PEXELS_API_KEY_UNSET` -> `videoPath: null`.
- **Scene 4 Query**: `"fresh cooked rotis bowl"` -> Status: `PEXELS_API_KEY_UNSET` -> `videoPath: null`.
- **Scene 5 Query**: `"bright clean clinic room"` -> Status: `PEXELS_API_KEY_UNSET` -> `videoPath: null`.

### 3. Denylist Test Verification
Tested `brollQuery: "belly fat"` against `docs/GUARDRAILS.md` denylist:
```json
{
  "n": 1,
  "query": "belly fat",
  "videoPath": null,
  "reason": "DENYLIST",
  "pexelsId": null,
  "pexelsUrl": null,
  "photographer": null,
  "photographerUrl": null
}
```
Result: Denylist correctly rejected the forbidden query with `reason: "DENYLIST"`.



