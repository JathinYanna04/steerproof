# Steer brand system (source of truth for code)

## Colour tokens
```ts
// render-service/src/brand.ts and frontend/tailwind.config.ts
export const brand = {
  cream:     "#F5EBDF", // main background on every post
  rust:      "#C04E2A", // headlines, key words, links
  maroon:    "#661414", // full-bleed bold/emotional slides
  ink:       "#351D1D", // body text on cream
  clay:      "#B58777", // secondary text, captions, labels
  blush:     "#E6CBBD", // dividers, card borders, subtle fills
  softPaper: "#F8F2EB", // cards/boxes on cream
} as const;
```
Rules: cream plus at most two accents per slide. On a maroon slide, text is cream and accents are blush. No other colours: no green, blue, neon, or gradients.

## Typography (Google Fonts, free)
| Role | Font | Use |
|---|---|---|
| Headline | **Instrument Serif** (regular + italic) | Mixed upright and italic in one headline: the word wrapped in `*…*` by the writer renders italic in rust |
| Body / labels | **Inter** (400, 500, 600) | Body copy, citations, labels |
| Field notes | **Caveat** (500) | Small handwritten asides only (e.g. "doctor's note", arrows) |

Import:
```html
<link href="https://fonts.googleapis.com/css2?family=Instrument+Serif:ital@0;1&family=Inter:wght@400;500;600&family=Caveat:wght@500&display=swap" rel="stylesheet">
```
For offline rendering, download the font files into `render-service/assets/fonts/` and use `@font-face`. Remotion should load them with `@remotion/google-fonts` or `staticFile` + FontFace.

Case: sentence case or lowercase only. Never ALL CAPS. The wordmark is the lowercase word **steer** in Instrument Serif.

## Layout (1080×1350 portrait post)
- Safe margins: 96 px left/right, 120 px top, 140 px bottom.
- Headline: 84–96 px, line-height 1.05, max 3 lines, colour ink with the italic phrase in rust.
- Body: Inter 36 px, line-height 1.4, ink, max 5 lines.
- Top-left label: Inter 24 px clay, e.g. `01 / steer notes`.
- Footer: wordmark `steer` (Instrument Serif 40 px, rust) bottom-left, and `steerclub.com` (Inter 22 px clay) bottom-right.
- Citation line on source slides and the static: Inter 22 px clay, e.g. `Source: Hall & Kahan, 2018 · Med Clin North Am`.
- Optional 1 px blush divider. Cards use softPaper with a 1 px blush border and 24 px radius.
- Leave at least 35% of the canvas empty.

## Copy limits (enforced by PolicyChecker)
| Field | Limit |
|---|---|
| Carousel headline | 48 chars |
| Carousel body | 160 chars |
| Static headline | 48 chars |
| Static support | 110 chars |
| Static footnote | 90 chars |
| Reel onScreen | 40 chars |
| Reel voiceover per scene | 30 words |
| Caption total | 1,800 chars |
| Hashtags | 8–12 |

## Voice spec (`ml-service/app/config/voice_spec.yaml`)
```yaml
personality: doctor-led, credible, warm, community-first ("a club, not a clinic"), calm
sentences: short, punchy; fragments are fine; second person ("you"); no jargon without a plain explanation
always_true_messages:
  - Doctor-led. Real doctors make the medical calls.
  - Safety first. Screening and blood tests come before any medicine.
  - Personal. Plans built on your labs, history and goals.
  - Built for India. Local meals, culture and appetite.
  - A planned exit. Habits that last after the medicine stops.
do:
  - Name the real worry ("will I gain it all back?") and answer honestly
  - Explain biology simply: it's biology, not willpower
  - Use Indian life: rice, rotis, chai, 3pm crashes, weddings, desk jobs, family dinners
  - Credit doctors and cite the source
dont:
  - fear, shame, body-shaming, before/after
  - promising kilos, timelines, miracle results
  - promoting a specific drug or brand
  - crowding slides
reference_lines:
  - "Lose well. Live well."
  - "Clinical weight loss, doctor-led."
  - "Metabolic care, calibrated for Indian biology."
  - "Before you start, hear it from a doctor, not a reel."
  - "Stop Googling. Ask a doctor."
signoff: "steer · steerclub.com"
cta_options:
  - "Questions? Ask a Steer doctor. steerclub.com"
  - "Stop Googling. Ask a doctor. steerclub.com"
```
