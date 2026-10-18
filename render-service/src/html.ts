import fs from 'fs';
import path from 'path';
import { brand, getCssVariables } from './brand.js';
import { escapeHtml, toHeadline } from './escape.js';
import { isClaim, resolveCitation, stripSignOff, dropCitationSentences, statFor } from './textRules.js';

const ASSETS_DIR = path.resolve(__dirname, '../assets');
const FONTS_DIR = path.join(ASSETS_DIR, 'fonts');

// Load fonts as base64 once at startup for instant offline font rendering
function getEmbeddedFontFaces(): string {
  try {
    const isReg = fs.readFileSync(path.join(FONTS_DIR, 'InstrumentSerif-Regular.woff2')).toString('base64');
    const isItalic = fs.readFileSync(path.join(FONTS_DIR, 'InstrumentSerif-Italic.woff2')).toString('base64');
    const interReg = fs.readFileSync(path.join(FONTS_DIR, 'Inter-Regular.woff2')).toString('base64');
    const interMed = fs.readFileSync(path.join(FONTS_DIR, 'Inter-Medium.woff2')).toString('base64');
    const interSemi = fs.readFileSync(path.join(FONTS_DIR, 'Inter-SemiBold.woff2')).toString('base64');
    const caveatMed = fs.readFileSync(path.join(FONTS_DIR, 'Caveat-Medium.woff2')).toString('base64');

    return `
      @font-face {
        font-family: 'Instrument Serif';
        font-style: normal;
        font-weight: 400;
        src: url('data:font/woff2;base64,${isReg}') format('woff2');
      }
      @font-face {
        font-family: 'Instrument Serif';
        font-style: italic;
        font-weight: 400;
        src: url('data:font/woff2;base64,${isItalic}') format('woff2');
      }
      @font-face {
        font-family: 'Inter';
        font-style: normal;
        font-weight: 400;
        src: url('data:font/woff2;base64,${interReg}') format('woff2');
      }
      @font-face {
        font-family: 'Inter';
        font-style: normal;
        font-weight: 500;
        src: url('data:font/woff2;base64,${interMed}') format('woff2');
      }
      @font-face {
        font-family: 'Inter';
        font-style: normal;
        font-weight: 600;
        src: url('data:font/woff2;base64,${interSemi}') format('woff2');
      }
      @font-face {
        font-family: 'Caveat';
        font-style: normal;
        font-weight: 500;
        src: url('data:font/woff2;base64,${caveatMed}') format('woff2');
      }
    `;
  } catch (err) {
    console.error('Error loading font files for embedding:', err);
    return '';
  }
}

const embeddedFonts = getEmbeddedFontFaces();

function getBaseStyles(width: number = 1080, height: number = 1350): string {
  return `
    ${getCssVariables()}
    ${embeddedFonts}

    * {
      box-sizing: border-box;
      margin: 0;
      padding: 0;
    }

    body {
      width: ${width}px;
      height: ${height}px;
      overflow: hidden;
      background-color: var(--cream);
      color: var(--ink);
      font-family: 'Inter', -apple-system, sans-serif;
      -webkit-font-smoothing: antialiased;
      position: relative;
    }

    .canvas {
      width: ${width}px;
      height: ${height}px;
      position: relative;
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      padding: 120px 96px 140px 96px;
      overflow: hidden;
    }

    .watermark-container {
      position: absolute;
      top: 0;
      left: 0;
      width: ${width}px;
      height: ${height}px;
      pointer-events: none;
      z-index: 2;
      display: flex;
      flex-direction: column;
      justify-content: space-around;
      align-items: center;
      transform: rotate(-25deg) scale(1.4);
      opacity: 0.12;
    }

    .watermark-text {
      font-family: 'Inter', sans-serif;
      font-size: 28px;
      font-weight: 600;
      color: var(--clay);
      letter-spacing: 2px;
      text-transform: lowercase;
      white-space: nowrap;
    }

    .top-label {
      font-family: 'Inter', sans-serif;
      font-size: 24px;
      font-weight: 500;
      color: var(--clay);
      letter-spacing: 1px;
      z-index: 10;
    }

    .footer-divider {
      width: 100%;
      height: 1px;
      background-color: var(--blush);
      margin-bottom: 24px;
    }

    .footer {
      display: flex;
      justify-content: space-between;
      align-items: flex-end;
      gap: 48px;
      width: 100%;
      z-index: 10;
    }

    .footer .wordmark {
      font-family: 'Instrument Serif', serif;
      font-size: 40px;
      line-height: 1;
      color: var(--rust);
    }

    .footer .url {
      font-family: 'Inter', sans-serif;
      font-size: 22px;
      font-weight: 500;
      color: var(--clay);
    }

    em.accent {
      font-family: 'Instrument Serif', serif;
      font-style: italic;
      color: var(--rust);
    }

    .citation-line {
      font-family: 'Inter', sans-serif;
      font-size: 22px;
      font-weight: 500;
      color: var(--clay);
      margin-top: 28px;
    }

    .caveat-aside {
      font-family: 'Caveat', cursive;
      font-size: 34px;
      color: var(--clay);
      margin-top: 24px;
    }

    /* Maroon overrides */
    body.theme-maroon {
      background-color: var(--maroon);
      color: var(--cream);
    }

    body.theme-maroon .top-label {
      color: var(--blush);
    }

    body.theme-maroon .footer-divider {
      background-color: rgba(230, 203, 189, 0.25);
    }

    body.theme-maroon .footer .wordmark {
      color: var(--cream);
    }

    body.theme-maroon .footer .url {
      color: var(--blush);
    }

    body.theme-maroon em.accent {
      color: var(--blush);
    }
  `;
}

function renderWatermark(draft: boolean): string {
  if (!draft) return '';
  return `
    <div class="watermark-container">
      <div class="watermark-text">draft · pending doctor review</div>
      <div class="watermark-text">draft · pending doctor review</div>
      <div class="watermark-text">draft · pending doctor review</div>
    </div>
  `;
}

export function buildSlideHtml(slide: any, totalSlides: number, citation: string, draft: boolean): string {
  const slideNum = slide.n || 1;
  const numFormatted = String(slideNum).padStart(2, '0');
  const type = slide.type || 'point';

  // Only the closing slide or a slide explicitly closing can be maroon
  const isClosing = type === 'closing' || slideNum === totalSlides;
  const isMaroon = isClosing && (slide.background === 'maroon' || type === 'closing');

  // The template draws the wordmark and steerclub.com, so copy never repeats them.
  const headlineRaw = stripSignOff(slide.headline || '');
  const bodyRaw = stripSignOff(slide.body || '');

  const headlineHtml = toHeadline(headlineRaw);
  const bodyHtml = escapeHtml(bodyRaw);

  // Citation lines only on claim slides and the source slide, and only from the
  // citation built from paper metadata, never from writer copy.
  const citationText = resolveCitation(slide, citation);
  const citationLine = isClaim(slide) && citationText
    ? `<div class="citation-line">${escapeHtml(citationText)}</div>`
    : '';

  let middleContent = '';

  if (type === 'hook') {
    middleContent = `
      <div style="flex: 1; display: flex; flex-direction: column; justify-content: center; max-width: 888px; z-index: 10;">
        <h1 data-block-id="slide${slideNum}.headline" style="font-family: 'Instrument Serif', serif; font-size: 96px; line-height: 1.05; font-weight: 400; color: var(--ink);">
          ${headlineHtml}
        </h1>
        <div class="caveat-aside">a doctor explains &rarr;</div>
      </div>
    `;
  } else if (type === 'illustration') {
    middleContent = `
      <div style="flex: 1; display: flex; flex-direction: column; justify-content: center; z-index: 10;">
        <div style="background-color: var(--soft-paper); border: 1px solid var(--blush); border-radius: 24px; padding: 56px;">
          <div style="font-family: 'Caveat', cursive; font-size: 30px; color: var(--clay); margin-bottom: 24px;">an everyday example</div>
          <h2 data-block-id="slide${slideNum}.headline" style="font-family: 'Instrument Serif', serif; font-size: 84px; line-height: 1.05; font-weight: 400; color: var(--ink); margin-bottom: 28px;">
            ${headlineHtml}
          </h2>
          <p data-block-id="slide${slideNum}.body" style="font-family: 'Inter', sans-serif; font-size: 36px; line-height: 1.4; color: var(--ink);">
            ${bodyHtml}
          </p>
        </div>
      </div>
    `;
  } else if (type === 'source') {
    const reviewStatusText = draft ? 'Pending doctor review' : 'Reviewed and approved for Steer';
    const caveat = dropCitationSentences(bodyRaw);
    middleContent = `
      <div style="flex: 1; display: flex; flex-direction: column; justify-content: center; max-width: 888px; z-index: 10;">
        <h2 data-block-id="slide${slideNum}.headline" style="font-family: 'Instrument Serif', serif; font-size: 84px; line-height: 1.05; font-weight: 400; color: var(--ink); margin-bottom: 32px;">
          Where this <em class="accent">comes</em> from
        </h2>
        <p style="font-family: 'Inter', sans-serif; font-size: 34px; line-height: 1.35; font-weight: 500; color: var(--ink); margin-bottom: 20px;">
          ${escapeHtml(citationText)}
        </p>
        ${caveat ? `<p data-block-id="slide${slideNum}.body" style="font-family: 'Inter', sans-serif; font-size: 30px; line-height: 1.4; color: var(--ink); margin-bottom: 24px;">
          ${escapeHtml(caveat)}
        </p>` : ''}
        <div style="font-family: 'Inter', sans-serif; font-size: 24px; color: var(--clay); font-weight: 500;">
          ${reviewStatusText}
        </div>
      </div>
    `;
  } else if (isClosing) {
    middleContent = `
      <div style="flex: 1; display: flex; flex-direction: column; justify-content: center; max-width: 888px; z-index: 10;">
        <h1 data-block-id="slide${slideNum}.headline" style="font-family: 'Instrument Serif', serif; font-size: 88px; line-height: 1.05; font-weight: 400; color: var(--cream); margin-bottom: 32px;">
          ${headlineHtml}
        </h1>
        ${bodyHtml ? `<p data-block-id="slide${slideNum}.body" style="font-family: 'Inter', sans-serif; font-size: 32px; line-height: 1.4; color: var(--cream); margin-bottom: 48px;">
          ${bodyHtml}
        </p>` : ''}
        <div style="font-family: 'Instrument Serif', serif; font-size: 72px; color: var(--cream); line-height: 1; margin-bottom: 12px;">
          steer
        </div>
        <div style="font-family: 'Inter', sans-serif; font-size: 24px; color: var(--blush); font-weight: 500;">
          steerclub.com
        </div>
      </div>
    `;
  } else {
    // Default: 'point' slide. Big number only for outcome values the
    // writer/validator flagged statEligible (never counts of studies or people).
    const stat = statFor(slide, bodyRaw);
    const statHeader = stat ? `
      <div data-block-id="slide${slideNum}.stat" style="font-family: 'Instrument Serif', serif; font-size: 160px; line-height: 1; color: var(--rust); margin-bottom: 20px;">
        ${escapeHtml(stat)}
      </div>
    ` : '';

    middleContent = `
      <div style="flex: 1; display: flex; flex-direction: column; justify-content: center; max-width: 888px; z-index: 10;">
        ${statHeader}
        <h2 data-block-id="slide${slideNum}.headline" style="font-family: 'Instrument Serif', serif; font-size: 84px; line-height: 1.05; font-weight: 400; color: var(--ink); margin-bottom: 32px;">
          ${headlineHtml}
        </h2>
        <p data-block-id="slide${slideNum}.body" style="font-family: 'Inter', sans-serif; font-size: 36px; line-height: 1.4; color: var(--ink);">
          ${bodyHtml}
        </p>
        ${citationLine}
      </div>
    `;
  }

  const themeClass = isMaroon ? 'theme-maroon' : '';

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>Slide ${slideNum}</title>
  <style>
    ${getBaseStyles(1080, 1350)}
  </style>
</head>
<body class="${themeClass}">
  ${renderWatermark(draft)}
  <div class="canvas">
    <div class="top-label" data-block-id="slide${slideNum}.label">
      ${numFormatted} / steer notes
    </div>

    ${middleContent}

    ${isClosing ? '' : `<div>
      <div class="footer-divider"></div>
      <div class="footer" data-block-id="slide${slideNum}.footer">
        <div class="wordmark">steer</div>
        <div class="url">steerclub.com</div>
      </div>
    </div>`}
  </div>
</body>
</html>`;
}

export function buildStaticHtml(
  staticContent: any,
  citation: string,
  draft: boolean,
  width: number = 1080,
  height: number = 1350
): string {
  const headlineHtml = toHeadline(stripSignOff(staticContent.headline || ''));
  const supportHtml = escapeHtml(stripSignOff(staticContent.support || ''));
  // Source line from the metadata-built citation only; the writer's footnote is ignored.
  const footnoteText = isClaim(staticContent) ? resolveCitation(staticContent, citation) : '';
  const footnoteHtml = escapeHtml(footnoteText);

  const isSquare = width === 1080 && height === 1080;
  const isStory = width === 1080 && height === 1920;

  const headlineSize = isSquare ? '80px' : isStory ? '96px' : '92px';
  const supportSize = isSquare ? '34px' : '38px';
  const paddingStyle = isStory ? 'padding: 220px 96px 240px 96px;' : 'padding: 120px 96px 140px 96px;';

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>Static Post</title>
  <style>
    ${getBaseStyles(width, height)}
    .canvas {
      ${paddingStyle}
    }
  </style>
</head>
<body>
  ${renderWatermark(draft)}
  <div class="canvas">
    <div class="top-label" data-block-id="static.label">
      steer notes
    </div>

    <div style="flex: 1; display: flex; flex-direction: column; justify-content: center; max-width: 888px; z-index: 10;">
      <h1 data-block-id="static.headline" style="font-family: 'Instrument Serif', serif; font-size: ${headlineSize}; line-height: 1.05; font-weight: 400; color: var(--ink); margin-bottom: 32px;">
        ${headlineHtml}
      </h1>
      <div style="width: 100%; height: 1px; background-color: var(--blush); margin-bottom: 32px;"></div>
      <p data-block-id="static.support" style="font-family: 'Inter', sans-serif; font-size: ${supportSize}; line-height: 1.4; color: var(--ink); margin-bottom: 32px;">
        ${supportHtml}
      </p>
      ${footnoteHtml ? `<div data-block-id="static.footnote" style="font-family: 'Inter', sans-serif; font-size: 22px; color: var(--clay); font-weight: 500;">
        ${footnoteHtml}
      </div>` : ''}
    </div>

    <div>
      <div class="footer-divider"></div>
      <div class="footer" data-block-id="static.footer">
        <div class="wordmark">steer</div>
        <div class="url">steerclub.com</div>
      </div>
    </div>
  </div>
</body>
</html>`;
}
