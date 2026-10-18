import path from 'path';
import fs from 'fs';
import { bundle } from '@remotion/bundler';
import { renderMedia, selectComposition } from '@remotion/renderer';
import { ReelProps } from '../remotion/types';

let bundlePromise: Promise<string> | null = null;
let isRendering = false;
const renderQueue: Array<() => Promise<any>> = [];

export function getBundleLocation(): Promise<string> {
  if (!bundlePromise) {
    const entryPoint = path.resolve(__dirname, '../remotion/index.ts');
    console.log('Bundling Remotion at startup from:', entryPoint);
    bundlePromise = bundle({ entryPoint })
      .then(loc => {
        console.log('Remotion bundle ready at:', loc);
        return loc;
      })
      .catch(err => {
        console.error('Failed to bundle Remotion:', err);
        bundlePromise = null;
        throw err;
      });
  }
  return bundlePromise;
}

// Queue execution helper to enforce 1 render at a time
async function processQueue() {
  if (isRendering || renderQueue.length === 0) return;
  isRendering = true;
  const task = renderQueue.shift();
  if (task) {
    try {
      await task();
    } catch (e) {
      console.error('Render task error in queue:', e);
    } finally {
      isRendering = false;
      processQueue();
    }
  }
}

export async function renderReel(
  paperId: string,
  inputProps: ReelProps,
  storageDir: string
): Promise<{ path: string; durationSec: number }> {
  return new Promise<{ path: string; durationSec: number }>((resolve, reject) => {
    const task = async () => {
      try {
        console.log(`Starting Remotion reel render for ${paperId}...`);
        const serveUrl = await getBundleLocation();

        const outDir = path.join(storageDir, 'renders', paperId);
        fs.mkdirSync(outDir, { recursive: true });
        const outputLocation = path.join(outDir, 'reel.mp4');

        const chromiumOptions: any = {
          args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage']
        };
        if (fs.existsSync('/usr/bin/chromium')) {
          chromiumOptions.executablePath = '/usr/bin/chromium';
        }

        console.log('Selecting SteerReel composition...');
        const composition = await selectComposition({
          serveUrl,
          id: 'SteerReel',
          inputProps,
          chromiumOptions
        });

        console.log(`Rendering ${composition.durationInFrames} frames at 30 fps to ${outputLocation}...`);

        await renderMedia({
          composition,
          serveUrl,
          codec: 'h264',
          outputLocation,
          inputProps,
          chromiumOptions,
          timeoutInMilliseconds: 600000, // 10 minutes
          onProgress: ({ progress }) => {
            const pct = Math.round(progress * 100);
            if (pct % 20 === 0) {
              console.log(`Render progress for ${paperId}: ${pct}%`);
            }
          }
        });

        const durationSec = composition.durationInFrames / 30;
        console.log(`Successfully rendered ${outputLocation} (${durationSec}s)`);

        resolve({
          path: `renders/${paperId}/reel.mp4`,
          durationSec
        });
      } catch (err) {
        console.error(`Remotion renderMedia failed for ${paperId}:`, err);
        reject(err);
      }
    };

    renderQueue.push(task);
    processQueue();
  });
}
