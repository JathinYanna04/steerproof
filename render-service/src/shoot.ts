import { chromium, Browser } from 'playwright';
import path from 'path';
import fs from 'fs';

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
  slide?: number;
  blockId: string;
  x: number;
  y: number;
  w: number;
  h: number;
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

  const templatePath = path.resolve(__dirname, '../templates/carousel.html');
  const fileUrl = `file://${templatePath}`;

  const paths: string[] = [];
  const allBlocks: BlockRect[] = [];

  for (let i = 0; i < slides.length; i++) {
    const slide = slides[i];
    const slideNum = slide.n || (i + 1);
    const page = await browser.newPage({
      viewport: { width: 1080, height: 1350 },
      deviceScaleFactor: 1
    });

    try {
      await page.addInitScript((data) => {
        (window as any).__DATA__ = data;
      }, {
        slide,
        totalSlides: slides.length,
        citation,
        draft
      });

      await page.goto(fileUrl, { waitUntil: 'networkidle' });
      await page.evaluate(() => document.fonts.ready);

      // Collect block bounding boxes
      const blocks = await page.$$eval('[data-block-id]', (els, sn) => {
        return els.map(e => {
          const r = e.getBoundingClientRect();
          return {
            slide: sn,
            blockId: e.getAttribute('data-block-id') || '',
            x: Math.round(r.x),
            y: Math.round(r.y),
            w: Math.round(r.width),
            h: Math.round(r.height)
          };
        });
      }, slideNum);

      allBlocks.push(...blocks);

      const filename = `${slideNum}.png`;
      const outPath = path.join(carouselDir, filename);
      await page.screenshot({
        path: outPath,
        type: 'png',
        clip: { x: 0, y: 0, width: 1080, height: 1350 }
      });

      paths.push(`renders/${paperId}/carousel/${filename}`);
    } finally {
      await page.close();
    }
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

  const templatePath = path.resolve(__dirname, '../templates/static.html');
  const fileUrl = `file://${templatePath}`;

  const page = await browser.newPage({
    viewport: { width: 1080, height: 1350 },
    deviceScaleFactor: 1
  });

  try {
    await page.addInitScript((data) => {
      (window as any).__DATA__ = data;
    }, {
      static: staticContent,
      citation,
      draft
    });

    await page.goto(fileUrl, { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);

    const blocks = await page.$$eval('[data-block-id]', (els) => {
      return els.map(e => {
        const r = e.getBoundingClientRect();
        return {
          blockId: e.getAttribute('data-block-id') || '',
          x: Math.round(r.x),
          y: Math.round(r.y),
          w: Math.round(r.width),
          h: Math.round(r.height)
        };
      });
    });

    const outPath = path.join(renderDir, 'static.png');
    await page.screenshot({
      path: outPath,
      type: 'png',
      clip: { x: 0, y: 0, width: 1080, height: 1350 }
    });

    return {
      paths: [`renders/${paperId}/static.png`],
      blocks
    };
  } finally {
    await page.close();
  }
}
