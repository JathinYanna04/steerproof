export interface Word {
  text: string;
  startMs: number;
  endMs: number;
}

export interface SceneData {
  n: number;
  onScreen: string;
  motion: 'title' | 'stat' | 'list' | 'quote' | 'closing';
  audioSrc: string;
  durationMs: number;
  words: Word[];
  voiceover?: string | null;
  brollSrc?: string | null;
  kind?: 'claim' | 'illustration' | 'brand';
  claimIds?: string[];
  citation?: string | null;
  statEligible?: boolean | null;
  stat?: string | number | null;
}

export interface ReelProps extends Record<string, unknown> {
  scenes: SceneData[];
  citation: string;
  draft: boolean;
  musicSrc?: string;
}

export const DEFAULT_SCENE_MS = 4000;
export const OUTRO_FRAMES = 30;

export function sceneFrames(s: Pick<SceneData, 'durationMs'>, fps: number): number {
  const ms = typeof s.durationMs === 'number' && s.durationMs > 0 ? s.durationMs : DEFAULT_SCENE_MS;
  return Math.ceil(((ms + 200) / 1000) * fps);
}
