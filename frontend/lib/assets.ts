// Shared asset-content helpers for the Gate 2 console: which blocks are claim-clickable,
// how a rendered block maps to kind + claimIds, and when each reel scene plays.

export type BlockKind = "claim" | "illustration" | "brand";

export interface Selection {
  blockId: string;
  kind: BlockKind;
  claimIds: string[];
  text?: string;
}

export interface Violation {
  id?: string | number;
  rule: string;
  severity: "BLOCKING" | "WARNING";
  detail?: string;
  location?: string;
  claimId?: string;
  resolved?: boolean;
}

/** The API sends rule/detail; older payloads used code/message. */
export function normalizeViolation(v: any): Violation {
  return {
    id: v.id ?? v.violationId,
    rule: v.rule ?? v.code ?? "UNKNOWN",
    severity: v.severity,
    detail: v.detail ?? v.message,
    location: v.location,
    claimId: v.claimId,
    resolved: !!v.resolved,
  };
}

// Only copy blocks can carry evidence. Labels, footers, wordmarks, watermarks, slide numbers and
// citation footnotes are brand chrome and never open a claim.
const CONTENT_FIELDS = new Set(["headline", "body", "stat", "support", "quote", "onScreen"]);

export function isContentBlock(blockId: string): boolean {
  const field = blockId.split(".").pop() || "";
  return CONTENT_FIELDS.has(field);
}

function asKind(k: any): BlockKind {
  return k === "claim" || k === "illustration" ? k : "brand";
}

/**
 * Resolve a rendered block to its kind + claim IDs. blockMap (written by the backend) wins;
 * otherwise fall back to the slide/static content the block was rendered from.
 */
export function resolveBlock(content: any, blockId: string): Selection {
  const [scope, field] = blockId.split(".");
  let source: any = null;
  const slideMatch = /^slide(\d+)$/.exec(scope || "");
  if (slideMatch) {
    source = (content?.slides || []).find((s: any) => Number(s.n) === Number(slideMatch[1]))
      ?? (content?.slides || [])[Number(slideMatch[1]) - 1];
  } else if (scope === "static") {
    source = content;
  }
  const mapped = content?.blockMap?.[blockId];
  const kind = asKind(mapped?.kind ?? source?.kind);
  const claimIds: string[] = kind === "claim" ? mapped?.claimIds ?? source?.claimIds ?? [] : [];
  const rawText = source && field ? source[field] : undefined;
  return { blockId, kind, claimIds, text: typeof rawText === "string" ? rawText : undefined };
}

/** Strip the *emphasis* markers the writer uses for italic accents. */
export function plainText(s?: string): string {
  return (s || "").replace(/\*/g, "");
}

export interface SceneSpan {
  n: number;
  start: number;
  end: number;
}

const FPS = 30;
// Mirrors render-service/remotion/Reel.tsx: each scene lasts ceil((audioMs + 200) / 1000 * fps) frames.
const SCENE_PAD_MS = 200;

function spansFromDurationsMs(scenes: any[], durationsMs: number[]): SceneSpan[] {
  let frames = 0;
  return scenes.map((s, i) => {
    const start = frames / FPS;
    frames += Math.ceil(((durationsMs[i] + SCENE_PAD_MS) / 1000) * FPS);
    return { n: Number(s.n ?? i + 1), start, end: frames / FPS };
  });
}

/**
 * Scene time spans, best source first: explicit start/end from the render, durationMs from the
 * render, measured per-scene voiceover audio, then an even split of the video length.
 */
export function sceneSpans(
  scenes: any[],
  audioDurationsMs: (number | null)[] | null,
  videoDuration: number | null
): SceneSpan[] | null {
  if (!scenes.length) return null;
  const startOf = (s: any) => s.startSec ?? s.start ?? (s.startMs != null ? s.startMs / 1000 : undefined);
  const endOf = (s: any) => s.endSec ?? s.end ?? (s.endMs != null ? s.endMs / 1000 : undefined);
  if (scenes.every((s) => startOf(s) != null && endOf(s) != null)) {
    return scenes.map((s, i) => ({ n: Number(s.n ?? i + 1), start: Number(startOf(s)), end: Number(endOf(s)) }));
  }
  if (scenes.every((s) => typeof s.durationMs === "number")) {
    return spansFromDurationsMs(scenes, scenes.map((s) => s.durationMs));
  }
  if (audioDurationsMs && audioDurationsMs.length === scenes.length && audioDurationsMs.every((d) => d != null)) {
    return spansFromDurationsMs(scenes, audioDurationsMs as number[]);
  }
  if (videoDuration && videoDuration > 0) {
    const each = videoDuration / scenes.length;
    return scenes.map((s, i) => ({ n: Number(s.n ?? i + 1), start: i * each, end: (i + 1) * each }));
  }
  return null;
}

export function activeSceneAt(spans: SceneSpan[] | null, t: number): number | null {
  if (!spans) return null;
  const hit = spans.find((s) => t >= s.start && t < s.end);
  if (hit) return hit.n;
  return t >= spans[spans.length - 1].end ? spans[spans.length - 1].n : null;
}
