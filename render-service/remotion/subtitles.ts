import type { Word } from './types';

export const MAX_CHUNK_WORDS = 5;

// A subtitle chunk never ends on one of these; the word moves to the next chunk.
export const NO_END_WORDS = new Set([
  'is', 'at', 'the', 'a', 'an', 'to', 'of', 'and', 'or', 'whether', 'that',
  'with', 'for', 'in', 'on', 'your', 'our',
  'but', 'by', 'from', 'as', 'than', 'into', 'after', 'are', 'was', 'be', 'their', 'its',
  'over', 'approximately', 'about', 'nearly', 'around', 'almost', 'least', 'very', 'more', 'less',
]);

// Words that usually open a new phrase: a good place to start the next chunk.
const PHRASE_STARTERS = new Set([
  'why', 'when', 'while', 'because', 'after', 'before', 'like', 'and', 'but', 'or', 'so',
  'to', 'of', 'in', 'for', 'with', 'over', 'within', 'by', 'that', 'which', 'who', 'than',
  'from', 'at', 'on', 'once', 'if', 'as', 'into', 'though', 'although', 'still', 'is',
  'are', 'was', 'were', 'approximately', 'about', 'nearly', 'around', 'almost',
]);

const norm = (s: string) => s.toLowerCase().replace(/[^\p{L}\p{N}%]/gu, '');
const endsClause = (s: string) => /[.,!?;:—–]["')\]]*$/.test(s.trim());

// TTS word boundaries come without punctuation. Re-attach it from the
// voiceover text so chunks can break at commas and full stops.
export function attachPunctuation(words: Word[], voiceover?: string | null): Word[] {
  if (!voiceover) return words;
  const tokens = voiceover.split(/\s+/).filter(Boolean);
  let t = 0;
  return words.map(w => {
    const target = norm(w.text);
    for (let k = t; k < Math.min(tokens.length, t + 4); k++) {
      if (norm(tokens[k]) === target) {
        t = k + 1;
        const trailing = tokens[k].match(/[.,!?;:—–]+["')\]]*$/);
        if (trailing && !endsClause(w.text)) return { ...w, text: w.text + trailing[0] };
        return w;
      }
    }
    return w;
  });
}

const SIGN_OFF_TOKEN = /^(?:steer|·|•|\||-|—|steerclub|steerclub\.com|\.com|www\.steerclub\.com)[.,!]?$/i;

// On the closing scene the wordmark is drawn on screen, so the spoken
// "steer · steerclub.com" is not repeated as a subtitle.
export function stripTrailingSignOff(words: Word[]): Word[] {
  let end = words.length;
  while (end > 0 && SIGN_OFF_TOKEN.test(words[end - 1].text.trim())) end--;
  if (end === words.length) return words;
  // "Ask a doctor at steerclub.com" must not leave "at" dangling.
  while (end > 0 && /^(?:at|on|to|via|visit)$/i.test(words[end - 1].text.trim())) end--;
  return words.slice(0, end);
}

function badEnd(w: Word): boolean {
  const raw = w.text.trim();
  if (endsClause(raw)) return false;
  // A bare number belongs with its unit ("100 calories"), so never end on it.
  if (/^[-−]?\d+(?:[.,]\d+)?$/.test(raw)) return true;
  return NO_END_WORDS.has(norm(raw));
}

const startsPhrase = (w: Word | undefined) => !w || PHRASE_STARTERS.has(norm(w.text));

// Chunk at punctuation and phrase boundaries, max 5 words per chunk, never
// ending a chunk on a function word. The final chunk may be shorter.
export function chunkWords(words: Word[], maxWords: number = MAX_CHUNK_WORDS): Word[][] {
  const out: Word[][] = [];
  let cur: Word[] = [];

  const flush = (n: number) => {
    out.push(cur.slice(0, n));
    cur = cur.slice(n);
  };

  for (let i = 0; i < words.length; i++) {
    const w = words[i];
    cur.push(w);
    if (cur.length >= 2 && endsClause(w.text)) {
      flush(cur.length);
      continue;
    }
    if (cur.length >= maxWords) {
      // Prefer the longest cut that does not end on a function word and where
      // the next word opens a phrase; else the longest cut that is not a bad end.
      let cut = 0;
      for (let c = cur.length; c >= 2 && !cut; c--) {
        const next = c < cur.length ? cur[c] : words[i + 1];
        if (!badEnd(cur[c - 1]) && startsPhrase(next)) cut = c;
      }
      for (let c = cur.length; c >= 2 && !cut; c--) {
        if (!badEnd(cur[c - 1])) cut = c;
      }
      flush(cut || cur.length);
    }
  }

  while (cur.length > 0) {
    if (cur.length <= maxWords) {
      flush(cur.length);
    } else {
      flush(maxWords);
    }
  }

  // A chunk that still ends on a function word (only possible at the very end
  // of a run) is merged forward when the merge stays within the limit.
  for (let i = 0; i < out.length - 1; i++) {
    const last = out[i][out[i].length - 1];
    if (badEnd(last) && out[i].length + out[i + 1].length <= maxWords) {
      out[i + 1] = [...out[i], ...out[i + 1]];
      out.splice(i, 1);
      i--;
    }
  }
  return out;
}
