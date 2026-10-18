// Pure text rules shared by the HTML templates and the Remotion reel.
// No Node imports here: this file is bundled into Remotion as well.

export type BlockKind = 'claim' | 'illustration' | 'brand';

// A block counts as a claim only when the writer marked it so. Older content
// without `kind` falls back to "has claim IDs".
export function isClaim(block: { kind?: string | null; claimIds?: unknown } | null | undefined): boolean {
  if (!block) return false;
  if (block.kind) return block.kind === 'claim';
  return Array.isArray(block.claimIds) && block.claimIds.length > 0;
}

// Citation text always comes from the backend (built from the paper's metadata
// row), never from writer copy. Per-block `citation` wins over the request-level one.
export function resolveCitation(
  block: { citation?: string | null } | null | undefined,
  requestCitation: string | null | undefined
): string {
  const own = block && typeof block.citation === 'string' ? block.citation.trim() : '';
  if (own) return own;
  return (requestCitation || '').trim();
}

const SIGN_OFF_RE =
  /\s*(?:\bsteer\s*[·•|—–-]\s*)?(?:https?:\/\/)?(?:www\.)?steerclub\.com\/?(?:[.!])?/gi;
const DANGLING_WORDMARK_RE = /\s*\bsteer\s*[·•|—–-]\s*$/i;

// The wordmark + steerclub.com are drawn by the template, so strip sign-off
// variants ("steer · steerclub.com", "steerclub.com") from copy.
export function stripSignOff(text: string | null | undefined): string {
  if (!text) return '';
  let out = text.replace(SIGN_OFF_RE, '');
  out = out.replace(DANGLING_WORDMARK_RE, '');
  out = out.replace(/\s+([.,!?;:])/g, '$1').replace(/\s{2,}/g, ' ').trim();
  return out.replace(/[\s·•|—–-]+$/, '').trim();
}

const CITATION_SENTENCE_RE =
  /\b(?:19|20)\d{2}\b|\bet al\b|\bdoi\b|\bjournal\b|\bmed clin\b|\bdiabetes obes metab\b|\bvol\.|\bpp\./i;

// Safety net for the source slide: drop writer sentences that look like a
// citation (years, "et al", journal names) and keep any plain caveat text.
export function dropCitationSentences(text: string | null | undefined): string {
  if (!text) return '';
  const sentences = text.match(/[^.!?]+[.!?]*/g) || [];
  return sentences
    .map(s => s.trim())
    .filter(s => s && !CITATION_SENTENCE_RE.test(s))
    .join(' ')
    .trim();
}

const STAT_RE = /(?<![\w.])[-−]?\d+(?:[.,]\d+)?\s?%?/;

// Big-number treatment only when the block is flagged statEligible by the
// writer/validator. Prefer its explicit `stat`, else the first number in the text.
export function statFor(
  block: { statEligible?: boolean | null; stat?: string | number | null } | null | undefined,
  fallbackText: string
): string | null {
  if (!block || block.statEligible !== true) return null;
  if (block.stat !== undefined && block.stat !== null && String(block.stat).trim()) {
    return String(block.stat).trim();
  }
  const m = (fallbackText || '').match(STAT_RE);
  return m ? m[0].trim() : null;
}

export function splitListItems(text: string | null | undefined): string[] {
  return (text || '')
    .split(/\s+[·;]\s+|\n+/)
    .map(s => s.trim())
    .filter(Boolean);
}
