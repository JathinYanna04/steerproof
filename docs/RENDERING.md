# Rendering (render-service: Node, Playwright, Remotion)

## Layout
```
render-service/
├── package.json
├── src/
│   ├── server.ts            # express routes /carousel /static /reel /health
│   ├── brand.ts             # tokens from docs/BRAND.md
│   ├── html.ts              # build HTML from templates + data
│   ├── shoot.ts             # Playwright screenshots + block rectangles
│   └── reel.ts              # Remotion bundle + renderMedia, queue
├── templates/
│   ├── base.css
│   ├── carousel.html        # one slide per render, data via window.__DATA__
│   └── static.html
├── assets/fonts/  assets/music/calm-1.mp3  assets/textures/paper.png (optional subtle grain)
└── remotion/
    ├── index.ts             # registerRoot
    ├── Root.tsx             # <Composition id="SteerReel" .../>
    ├── Reel.tsx
    ├── scenes/ TitleScene.tsx StatScene.tsx ListScene.tsx QuoteScene.tsx ClosingScene.tsx
    └── components/ Subtitles.tsx BrollLayer.tsx Wordmark.tsx Headline.tsx
```

## Headline italics
Writer marks the italic phrase with `*…*`. Convert it safely (escape HTML first):
```ts
const toHeadline = (s: string) =>
  escapeHtml(s).replace(/\*(.+?)\*/g, '<em class="accent">$1</em>');
```
CSS: `em.accent { font-style: italic; color: var(--rust); }`.

## Carousel / static via Playwright
- Each template is a single HTML page at the exact canvas size (`1080×1350`). The data is injected with `page.addInitScript(d => window.__DATA__ = d, data)`, and the template renders it with vanilla JS.
- Every text element has `data-block-id="slide3.headline"`. After render, collect the rectangles:
```ts
const blocks = await page.$$eval('[data-block-id]', els => els.map(e => {
  const r = e.getBoundingClientRect();
  return { blockId: e.dataset.blockId, x: r.x, y: r.y, w: r.width, h: r.height };
}));
```
- Wait for fonts (`await page.evaluate(() => document.fonts.ready)`) before the screenshot.
- `page.screenshot({ path, type: 'png', clip: { x:0, y:0, width:1080, height:1350 } })`.
- Reuse a single browser instance and open a new page per slide.

### Slide type designs
| Type | Design |
|---|---|
| hook | Large headline centred vertically (96 px), label `01 / steer notes`, a small Caveat aside optional ("a doctor explains →") |
| point | Label number, headline 84 px, body 36 px, optional big stat in rust Instrument Serif 160 px when the body has a number |
| illustration | softPaper card containing the example, Caveat note "an everyday example". No statistics allowed |
| source | "Where this comes from" headline, citation in Inter, "Reviewed by a Steer doctor" line (after approval only; before approval it reads "Pending doctor review") |
| closing | Maroon background allowed, cream headline, CTA line, wordmark large |

The static uses the same system: headline, support line, footnote citation, wordmark. Render it at 1080×1350 by default; bonus sizes are 1080×1080 and 1080×1920 with adjusted margins.

### Draft watermark
Until an asset is APPROVED, render a diagonal 28 px clay text at 12% opacity: `draft · pending doctor review`. Approval re-renders without the watermark (fast, since only PNGs change). For the reel, the watermark is a top-right corner label instead of diagonal text, and approval re-renders it.

## Reel via Remotion
- Size 1080×1920, 30 fps. Duration = sum of scene audio durations + 0.4 s padding per scene + 1.5 s outro.
- Input props:
```ts
type ReelProps = {
  scenes: { n:number; onScreen:string; motion:'title'|'stat'|'list'|'quote'|'closing';
            audioSrc:string; durationMs:number; words:{text:string; startMs:number; endMs:number}[];
            brollSrc:string|null }[];
  citation: string; draft: boolean; musicSrc: string;
};
```
- Use `calculateMetadata` to compute `durationInFrames` from props.
- Render server-side:
```ts
import { bundle } from '@remotion/bundler';
import { renderMedia, selectComposition } from '@remotion/renderer';
const serveUrl = await bundle({ entryPoint: 'remotion/index.ts' });  // bundle once at startup
const composition = await selectComposition({ serveUrl, id: 'SteerReel', inputProps });
await renderMedia({ composition, serveUrl, codec: 'h264', outputLocation, inputProps });
```
- Media files are served to Remotion over HTTP: run express static on `STORAGE_DIR` at `/files` and pass absolute `http://localhost:8002/files/...` URLs.

### Visual layers per scene (bottom to top)
1. **Background:** cream, or the b-roll video `<OffthreadVideo muted>` with `objectFit: cover`, then a cream overlay at 55% opacity plus a slight blur (`filter: blur(2px)`) so the text stays readable and the brand stays calm. If there is no b-roll, use a cream background with slowly drifting blush shapes.
2. **Motion text:** by `motion` type
   - `title`: headline fades and rises over 12 frames; the italic word draws in after a 6-frame delay.
   - `stat`: big rust number counts up with `interpolate` (only if the number exists in the onScreen text).
   - `list`: up to 3 short lines stagger in.
   - `quote`: softPaper card with a short source line in Inter clay.
   - `closing`: maroon background, cream headline, wordmark, `steerclub.com`.
3. **Subtitles:** bottom third, Inter 44 px, ink on a softPaper pill. Show 3–5 words at a time from `words`, with the active word in rust. Use sentence case.
4. **Persistent:** small `steer` wordmark top-left. Citation in Inter 22 px clay at the bottom during scenes linked to claims.
5. **Audio:** scene voiceover `<Audio>` in a `<Sequence>`, plus music at volume 0.08 with a fade in and out.

### Fallback if Remotion fails
`ffmpeg` (free): build per-scene stills with Playwright (same HTML system at 1080×1920), then
```
ffmpeg -loop 1 -t {dur} -i scene1.png -i scene1.mp3 ... -filter_complex "zoompan..." -c:v libx264 -pix_fmt yuv420p reel.mp4
```
and burn subtitles from a generated `.ass` file. Only use this if Remotion is blocked.

## Performance
- Carousel of 6 slides: under 10 s. Reel of 35 s: 1–3 min locally (acceptable).
- Render one reel at a time; queue the rest.
