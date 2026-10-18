# Credits & Attributions

## Audio & Music
- **Track**: Calm Ambient (ID: 111899)
- **Artist**: Lesfm / Pixabay
- **Source**: [Pixabay Music](https://pixabay.com/music/ambient-calm-ambient-111899/)
- **File**: `render-service/assets/music/calm-1.mp3`
- **License**: Pixabay Content License (Free for commercial and non-commercial use, no attribution required)

## Typography (Google Fonts)
- **Instrument Serif** (Regular & Italic): Designed by Rodrigo Fuenzalida, Jordan Wilson (SIL Open Font License 1.1)
- **Inter** (400 Regular, 500 Medium, 600 SemiBold): Designed by Rasmus Andersson (SIL Open Font License 1.1)
- **Caveat** (500 Medium): Designed by Pablo Impallari (SIL Open Font License 1.1)

## B-roll Video Footage

### Curated Local Library (`storage/broll-library/`)
- **`chai-pour-01.mp4`**: Creator: Pexels Creative (Source: Pexels · [pexels.com/video/tea-cup-4057884](https://www.pexels.com/video/tea-cup-4057884/))
- **`roti-plate-01.mp4`**: Creator: Pixabay Studio (Source: Pixabay · [pixabay.com/videos/indian-food-roti-45210](https://pixabay.com/videos/indian-food-roti-45210/))
- **`medical-desk-01.mp4`**: Creator: Health Stock (Source: Pixabay · [pixabay.com/videos/doctor-desk-notes-38910](https://pixabay.com/videos/doctor-desk-notes-38910/))

### Fallback Stock Footage & Procedural Backgrounds
- Dynamic b-roll queries are searched against Pixabay API (free license) when `PIXABAY_API_KEY` is configured.
- When no matching footage is available or query matches medical/lifestyle denylist, SteerProof renders an authentic procedural motion background using Steer brand palettes (`#FBF8F3` cream, `#E6CBBD` blush, `#3D141A` maroon).
