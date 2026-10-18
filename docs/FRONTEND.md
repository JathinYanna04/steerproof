# Frontend (Next.js App Router + Tailwind)

## Tailwind config
Extend `colors` with the brand tokens and `fontFamily` with `serif: ['Instrument Serif']`, `sans: ['Inter']`, `hand: ['Caveat']`. The app UI uses the same calm brand: cream background, ink text, rust primary buttons.

## Routes
| Route | Purpose |
|---|---|
| `/` | Upload (drag-drop PDF, or paste a URL, optional theme, doctor name stored in localStorage) + list of papers with status chips |
| `/batch` | Upload up to 5 PDFs → batch → link to `/batch/[id]` |
| `/batch/[id]` | Week plan: 7 columns Mon–Sun, asset cards, drag to reschedule, `.ics` download |
| `/papers/[id]` | Pipeline view: stage stepper with timings, LLM call and cache counts, errors with a Retry button, stats card |
| `/papers/[id]/claims` | **Gate 1** claims review console |
| `/papers/[id]/assets` | **Gate 2** asset review with click-to-source |
| `/papers/[id]/export` | Export button, blockedBy list, download link, metrics summary |

## Gate 1: claims console (`/papers/[id]/claims`)
Split layout:
- **Left (40%):** claim list sorted by risk (RED first), then by finding class. Each card shows:
  - scientific claim (bold) and consumer draft (muted)
  - badges: finding class, risk light (green/amber/red dot + reason on hover), population fit chip + reason, `NEEDS EVIDENCE` in red if applicable
  - quote score (e.g. "quote match 97") and numbers ✓/✗
  - limitations (collapsible)
  - buttons: Approve · Edit · Reject (reason modal)
- **Right (60%):** source viewer showing the page PNG with a translucent rust highlight over the bbox, page and section label, and the full passage text below. Clicking a card scrolls the viewer to it.
- Keyboard shortcuts: `j`/`k` next/prev, `a` approve, `e` edit, `r` reject. Shortcuts make the review visibly fast in the Loom.
- Review timer: measure the time each card is focused and send `reviewSeconds` with the PATCH.
- Top bar: progress "9 / 12 reviewed", and a "Generate content" button that is disabled with the gate reasons as a tooltip until Gate 1 passes.
- Edit modal: textarea + optional "re-link evidence" (choose a passage from the same page/section). Saving re-runs verification.

Highlight overlay math:
```ts
const scale = imgRenderedWidth / passage.pageWidth;
style = { left: bbox.l*scale, top: bbox.t*scale, width: (bbox.r-bbox.l)*scale, height: (bbox.b-bbox.t)*scale };
```

## Gate 2: assets console (`/papers/[id]/assets`)
- Tabs: Carousel · Static · Reel · Caption.
- **Carousel/static:** show the PNG at its real aspect ratio. Overlay absolutely-positioned transparent buttons from `blocks`; hover outlines them in rust, and clicking opens a side drawer with:
  - `claim` blocks → linked claims, quote, page highlight thumbnail, "open full source"
  - `illustration` blocks → "Illustration, not a study finding"
  - `brand` blocks → "Brand line"
- **Reel:** HTML5 video + a scene list synced to `currentTime`. Clicking a scene shows its claims the same way.
- **Caption:** paragraphs with small claim chips, plus hashtags and a copy button.
- **Violations panel:** list with severity colours. BLOCKING violations disable Approve.
- **Writer reasoning box:** shows `coreMessage` and `reasoning` (needed for the submission).
- Status chip per asset (`RENDERED`, `BLOCKED`, `STALE`, `APPROVED`). Buttons: Approve · Regenerate.

## Export page
A big "Export approved content" button. A 422 renders `blockedBy` as a readable checklist with links. A success shows the download link and the metrics card (human time, review time, pipeline time, claims stats).

## Data fetching
- `lib/api.ts`: a typed fetch wrapper. Base URL is `NEXT_PUBLIC_API_URL`.
- Poll `/api/papers/{id}` every 2 s while status is non-terminal.
- Wrap mutations with toasts, and show server error messages verbatim.

## UX polish that matters for judging
- An empty state on upload with a sample paper button ("Try with Hall & Kahan 2018").
- A live stage stepper (Parsing → Extracting → Verifying → Review → Writing → Rendering → Review → Export) with a spinner and elapsed seconds.
- A stats card always visible on the paper page: "Human time: 9 min 40 s · Pipeline: 4 min 12 s · 12 claims · 2 flagged · 1 edited · 1 rejected".
