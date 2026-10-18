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
  brollSrc?: string | null;
  kind?: string;
  claimIds?: string[];
}

export interface ReelProps {
  scenes: SceneData[];
  citation: string;
  draft: boolean;
  musicSrc?: string;
}
