import { chromium, Browser } from 'playwright';
import path from 'path';
import fs from 'fs';
import { buildSlideHtml, buildStaticHtml } from './html.js';

let browserInstance: Browser | null = null;

export async function getBrowser(): Promise<Browser> {
  if (!browserInstance) {
    const launchOptions: any = {
      headless: true,
      args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage']
    };
    if (fs.existsSync('/usr/bin/chromium')) {
      launchOptions.executablePath = '/usr/bin/chromium';
    }
    browserInstance = await chromium.launch(launchOptions);
  }
  return browserInstance;
}

export interface BlockRect {
  image?: string;
  slide?: number;
  blockId: string;
  x: number;
  y: number;
  w: number;
  h: number;
}

export class TextOverflowError extends Error {
  blockId: string;
  constructor(blockId: string, message: string) {
    super(message);
    this.name = 'TextOverflowError';
    this.blockId = blockId;
  }
}

export class FontNotLoadedError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'FontNotLoadedError';
  }
}

async function renderAndCapture(
  browser: Browser,
  html: string,
  width: number,
  height: number,
  outPath: string,
  imageRelPath: string
): Promise<BlockRect[]> {
  const page = await browser.newPage({
    viewport: { width, height },
    deviceScaleFactor: 1
  });

  try {
    await page.setContent(html, { waitUntil: 'load' });
    await page.evaluate(() => document.fonts.ready);

    // Verify fonts loaded
    const fontLoaded = await page.evaluate(() => {
      return document.fonts.check('96px "Instrument Serif"');
    });

    if (!fontLoaded) {
      throw new FontNotLoadedError('FONT_NOT_LOADED: Instrument Serif 96px failed verification');
    }

    // Text fitting check and step-down
    const fitResult = await page.evaluate(() => {
      // Check headlines (max 3 lines, step down by 4px to min 64px)
      const headlines = Array.from(document.querySelectorAll<HTMLElement>('[data-block-id$=".headline"]'));
      for (const el of headlines) {
        let currentSize = parseFloat(window.getComputedStyle(el).fontSize) || 84;
        let lh = parseFloat(window.getComputedStyle(el).lineHeight) || (currentSize * 1.05);
        let lines = Math.round(el.offsetHeight / lh);

        while (lines > 3 && currentSize > 64) {
          currentSize -= 4;
          el.style.fontSize = `${currentSize}px`;
          lh = parseFloat(window.getComputedStyle(el).lineHeight) || (currentSize * 1.05);
          lines = Math.round(el.offsetHeight / lh);
        }

        if (lines > 3) {
          return { overflow: true, blockId: el.getAttribute('data-block-id') || 'headline' };
        }
      }

      // Check bodies (max 5 lines, step down by 4px to min 30px)
      const bodies = Array.from(document.querySelectorAll<HTMLElement>('[data-block-id$=".body"], [data-block-id$=".support"]'));
      for (const el of bodies) {
        let currentSize = parseFloat(window.getComputedStyle(el).fontSize) || 36;
        let lh = parseFloat(window.getComputedStyle(el).lineHeight) || (currentSize * 1.4);
        let lines = Math.round(el.offsetHeight / lh);

        while (lines > 5 && currentSize > 30) {
          currentSize -= 4;
          el.style.fontSize = `${currentSize}px`;
          lh = parseFloat(window.getComputedStyle(el).lineHeight) || (currentSize * 1.4);
          lines = Math.round(el.offsetHeight / lh);
        }

        if (lines > 5) {
          return { overflow: true, blockId: el.getAttribute('data-block-id') || 'body' };
        }
      }

      return { overflow: false, blockId: '' };
    });

    if (fitResult.overflow) {
      throw new TextOverflowError(fitResult.blockId, `TEXT_OVERFLOW on ${fitResult.blockId}`);
    }

    // Collect block bounding boxes
    const blocks = await page.$$eval('[data-block-id]', (els, imgName) => {
      return els.map(e => {
        const r = e.getBoundingClientRect();
        return {
          image: imgName,
          blockId: e.getAttribute('data-block-id') || '',
          x: Math.round(r.x),
          y: Math.round(r.y),
          w: Math.round(r.width),
          h: Math.round(r.height)
        };
      });
    }, imageRelPath);

    fs.mkdirSync(path.dirname(outPath), { recursive: true });
    await page.screenshot({
      path: outPath,
      type: 'png',
      clip: { x: 0, y: 0, width, height }
    });

    return blocks;
  } finally {
    await page.close();
  }
}

export async function shootCarousel(
  paperId: string,
  slides: any[],
  citation: string,
  draft: boolean,
  storageDir: string
): Promise<{ paths: string[]; blocks: BlockRect[] }> {
  const browser = await getBrowser();
  const carouselDir = path.join(storageDir, 'renders', paperId, 'carousel');
  fs.mkdirSync(carouselDir, { recursive: true });

  const paths: string[] = [];
  const allBlocks: BlockRect[] = [];

  for (let i = 0; i < slides.length; i++) {
    const slide = slides[i];
    const slideNum = slide.n || (i + 1);
    const html = buildSlideHtml(slide, slides.length, citation, draft);

    const filename = `${slideNum}.png`;
    const outPath = path.join(carouselDir, filename);
    const relPath = `renders/${paperId}/carousel/${filename}`;

    const blocks = await renderAndCapture(browser, html, 1080, 1350, outPath, `carousel/${filename}`);
    blocks.forEach(b => { b.slide = slideNum; });
    allBlocks.push(...blocks);
    paths.push(relPath);
  }

  return { paths, blocks: allBlocks };
}

export async function shootStatic(
  paperId: string,
  staticContent: any,
  citation: string,
  draft: boolean,
  storageDir: string
): Promise<{ paths: string[]; blocks: BlockRect[] }> {
  const browser = await getBrowser();
  const renderDir = path.join(storageDir, 'renders', paperId);
  fs.mkdirSync(renderDir, { recursive: true });

  const paths: string[] = [];
  const allBlocks: BlockRect[] = [];

  // 1. Default portrait 1080x1350
  const portraitHtml = buildStaticHtml(staticContent, citation, draft, 1080, 1350);
  const portraitOut = path.join(renderDir, 'static.png');
  const portraitRel = `renders/${paperId}/static.png`;
  const portraitBlocks = await renderAndCapture(browser, portraitHtml, 1080, 1350, portraitOut, 'static.png');
  paths.push(portraitRel);
  allBlocks.push(...portraitBlocks);

  // 2. Bonus square 1080x1080
  const squareHtml = buildStaticHtml(staticContent, citation, draft, 1080, 1080);
  const squareOut = path.join(renderDir, 'static-1080x1080.png');
  const squareRel = `renders/${paperId}/static-1080x1080.png`;
  await renderAndCapture(browser, squareHtml, 1080, 1080, squareOut, 'static-1080x1080.png');
  paths.push(squareRel);

  // 3. Bonus story 1080x1920
  const storyHtml = buildStaticHtml(staticContent, citation, draft, 1080, 1920);
  const storyOut = path.join(renderDir, 'static-1080x1920.png');
  const storyRel = `renders/${paperId}/static-1080x1920.png`;
  await renderAndCapture(browser, storyHtml, 1080, 1920, storyOut, 'static-1080x1920.png');
  paths.push(storyRel);

  return { paths, blocks: allBlocks };
}
