import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  chunkWords,
  attachPunctuation,
  stripTrailingSignOff,
  NO_END_WORDS,
  MAX_CHUNK_WORDS,
} from '../remotion/subtitles';
import type { Word } from '../remotion/types';

const toWords = (s: string): Word[] =>
  s.split(/\s+/).filter(Boolean).map((text, i) => ({ text, startMs: i * 300, endMs: i * 300 + 280 }));

const bare = (s: string) => s.toLowerCase().replace(/[^\p{L}\p{N}%]/gu, '');
const endsPunct = (s: string) => /[.,!?;:—–]["')\]]*$/.test(s);

function assertChunkRules(chunks: Word[][]) {
  chunks.forEach((c, i) => {
    assert.ok(c.length <= MAX_CHUNK_WORDS, `chunk ${i} has ${c.length} words`);
    const last = c[c.length - 1].text;
    if (i < chunks.length - 1 && !endsPunct(last)) {
      assert.ok(!NO_END_WORDS.has(bare(last)), `chunk ${i} ends on "${last}"`);
    }
  });
}

test('never more than 5 words and never ends on a function word', () => {
  const vo =
    'When weight is lost the body triggers compensatory biological changes that often make weight regain common';
  const chunks = chunkWords(toWords(vo));
  assertChunkRules(chunks);
  assert.equal(chunks.flat().map(w => w.text).join(' '), vo);
});

test('breaks at punctuation recovered from the voiceover', () => {
  const vo = 'Care must include a planned exit for real life. Clinical weight loss, doctor-led.';
  const words = attachPunctuation(toWords(vo.replace(/[.,]/g, '')), vo);
  const chunks = chunkWords(words).map(c => c.map(w => w.text).join(' '));
  assert.ok(chunks.some(c => c.endsWith('life.')), JSON.stringify(chunks));
  assert.ok(chunks.some(c => c.endsWith('loss,') || c.endsWith('doctor-led.')), JSON.stringify(chunks));
  assertChunkRules(chunkWords(words));
});

test('each listed function word is moved forward instead of ending a chunk', () => {
  for (const fw of ['is', 'at', 'the', 'a', 'an', 'to', 'of', 'and', 'or', 'whether', 'that', 'with', 'for', 'in', 'on', 'your', 'our']) {
    const vo = `Nearly half still maintained ${fw} weight loss one year later`;
    assertChunkRules(chunkWords(toWords(vo)));
  }
});

test('final chunk may be shorter', () => {
  const chunks = chunkWords(toWords('Participants regained most lost weight over one year'));
  assert.equal(chunks.flat().length, 8);
  assert.ok(chunks[chunks.length - 1].length <= MAX_CHUNK_WORDS);
});

test('closing sign-off words are dropped from subtitles', () => {
  const words = toWords('Clinical weight loss, doctor-led. steer · steerclub.com');
  const kept = stripTrailingSignOff(words).map(w => w.text);
  assert.deepEqual(kept, ['Clinical', 'weight', 'loss,', 'doctor-led.']);
});

test('a number stays with its unit', () => {
  const vo = 'appetite increased by approximately 100 calories daily while resting burn dropped slightly';
  const chunks = chunkWords(toWords(vo)).map(c => c.map(w => w.text).join(' '));
  assert.ok(chunks.some(c => c.includes('100 calories')), JSON.stringify(chunks));
});

test('no dangling preposition after the sign-off is removed', () => {
  const kept = stripTrailingSignOff(toWords('Stop Googling. Ask a doctor at steerclub.com')).map(w => w.text);
  assert.deepEqual(kept, ['Stop', 'Googling.', 'Ask', 'a', 'doctor']);
});
