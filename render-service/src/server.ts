import express from 'express';
import cors from 'cors';
import path from 'path';
import { shootCarousel, shootStatic } from './shoot.js';

const app = express();
const PORT = process.env.PORT || 8002;
const STORAGE_DIR = process.env.STORAGE_DIR || path.resolve(__dirname, '../../storage');

app.use(cors());
app.use(express.json({ limit: '50mb' }));

// Serve storage files for external preview or remotion if needed
app.use('/files', express.static(STORAGE_DIR));
app.use('/assets', express.static(path.resolve(__dirname, '../assets')));

app.get('/health', (req, res) => {
  res.json({ status: 'ok', service: 'render-service', port: PORT });
});

app.post('/carousel', async (req, res) => {
  try {
    const { paperId, slides, citation, draft } = req.body;
    if (!paperId || !slides || !Array.isArray(slides)) {
      return res.status(400).json({ error: 'INVALID_INPUT', message: 'paperId and slides array required' });
    }

    const isDraft = draft !== undefined ? Boolean(draft) : true;
    const result = await shootCarousel(paperId, slides, citation || '', isDraft, STORAGE_DIR);
    res.json(result);
  } catch (err: any) {
    console.error('Error in /carousel:', err);
    if (err.name === 'TextOverflowError') {
      return res.status(422).json({ error: 'TEXT_OVERFLOW', blockId: err.blockId, message: err.message });
    }
    if (err.name === 'FontNotLoadedError') {
      return res.status(500).json({ error: 'FONT_NOT_LOADED', message: err.message });
    }
    res.status(500).json({ error: 'RENDER_FAILED', message: err.message });
  }
});

app.post('/static', async (req, res) => {
  try {
    const { paperId, static: staticData, citation, draft } = req.body;
    const content = staticData || req.body.content;
    if (!paperId || !content) {
      return res.status(400).json({ error: 'INVALID_INPUT', message: 'paperId and static content required' });
    }

    const isDraft = draft !== undefined ? Boolean(draft) : true;
    const result = await shootStatic(paperId, content, citation || '', isDraft, STORAGE_DIR);
    res.json(result);
  } catch (err: any) {
    console.error('Error in /static:', err);
    if (err.name === 'TextOverflowError') {
      return res.status(422).json({ error: 'TEXT_OVERFLOW', blockId: err.blockId, message: err.message });
    }
    if (err.name === 'FontNotLoadedError') {
      return res.status(500).json({ error: 'FONT_NOT_LOADED', message: err.message });
    }
    res.status(500).json({ error: 'RENDER_FAILED', message: err.message });
  }
});

// Reel endpoint (placeholder for Section D)
app.post('/reel', async (req, res) => {
  res.json({ status: 'ok', message: 'Reel rendering will be handled in section D' });
});

app.listen(PORT, () => {
  console.log(`Render service listening on port ${PORT}, STORAGE_DIR: ${STORAGE_DIR}`);
});
