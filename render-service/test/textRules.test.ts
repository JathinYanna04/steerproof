import { test } from 'node:test';
import assert from 'node:assert/strict';
import { stripSignOff, dropCitationSentences, statFor, isClaim, resolveCitation, splitListItems } from '../src/textRules';
import { buildSlideHtml, buildStaticHtml } from '../src/html';

test('stripSignOff removes wordmark + url variants and keeps the rest', () => {
  assert.equal(
    stripSignOff('Clinical weight loss, doctor-led. Stop Googling. Ask a doctor. steer · steerclub.com'),
    'Clinical weight loss, doctor-led. Stop Googling. Ask a doctor.'
  );
  assert.equal(stripSignOff('Questions? Ask a Steer doctor. steerclub.com'), 'Questions? Ask a Steer doctor.');
});

test('dropCitationSentences keeps caveats, drops writer-written citations', () => {
  assert.equal(
    dropCitationSentences(
      'Data from Hall and Kahan (2018), Med Clin North Am. Note that this extension trial had a smaller participant sample.'
    ),
    'Note that this extension trial had a smaller participant sample.'
  );
});

test('stat only when statEligible is true', () => {
  assert.equal(statFor({ statEligible: false }, '29 studies'), null);
  assert.equal(statFor({}, '29 studies'), null);
  assert.equal(statFor({ statEligible: true }, 'about 5% weight loss'), '5%');
  assert.equal(statFor({ statEligible: true, stat: '100 kcal' }, 'x'), '100 kcal');
});

test('isClaim / resolveCitation / list items', () => {
  assert.equal(isClaim({ kind: 'brand', claimIds: ['C-1'] }), false);
  assert.equal(isClaim({ kind: 'claim' }), true);
  assert.equal(isClaim({ claimIds: ['C-1'] }), true);
  assert.equal(resolveCitation({ citation: 'Wilding et al., 2022' }, 'other'), 'Wilding et al., 2022');
  assert.equal(resolveCitation({}, 'Hall et al., 2018'), 'Hall et al., 2018');
  assert.deepEqual(splitListItems('Nearly half maintained weight loss.'), ['Nearly half maintained weight loss.']);
  assert.equal(splitListItems('one · two · three').length, 3);
});

const CIT = 'Wilding et al., 2022 · Diabetes Obes Metab';

test('carousel: no stat for study counts, citation only on claim + source slides', () => {
  const countSlide = buildSlideHtml(
    { n: 4, type: 'point', kind: 'claim', statEligible: false, headline: 'Across *29* studies', body: '29 studies were reviewed.' },
    7, CIT, true
  );
  assert.ok(!countSlide.includes('slide4.stat'));
  assert.ok(countSlide.includes(CIT));

  const statSlide = buildSlideHtml(
    { n: 2, type: 'point', kind: 'claim', statEligible: true, stat: '100 kcal', headline: 'h', body: 'b' },
    7, CIT, true
  );
  assert.ok(statSlide.includes('slide2.stat') && statSlide.includes('100 kcal'));

  const brandSlide = buildSlideHtml({ n: 1, type: 'hook', kind: 'brand', headline: 'Will you *regain*?' }, 7, CIT, true);
  assert.ok(!brandSlide.includes(CIT));
  const illus = buildSlideHtml({ n: 5, type: 'illustration', kind: 'illustration', headline: 'h', body: 'b' }, 7, CIT, true);
  assert.ok(!illus.includes(CIT));

  const source = buildSlideHtml(
    { n: 6, type: 'source', kind: 'claim', headline: 'x', body: 'Data from Hall and Kahan (2018), Med Clin North Am.' },
    7, CIT, true
  );
  assert.ok(source.includes(CIT));
  assert.ok(!source.includes('Hall and Kahan'));
});

test('closing slide: one sign-off, no standard footer, body stripped', () => {
  const html = buildSlideHtml(
    { n: 7, type: 'closing', kind: 'brand', headline: 'A planned exit', body: 'Ask a doctor. steer · steerclub.com' },
    7, CIT, true
  );
  assert.equal(html.match(/steerclub\.com/g)?.length, 1);
  assert.ok(!html.includes('class="footer"'));
});

test('footer uses real CSS spacing (no camelCase property)', () => {
  const html = buildSlideHtml({ n: 2, type: 'point', kind: 'brand', headline: 'h', body: 'b' }, 7, CIT, true);
  assert.ok(!html.includes('justifyContent'));
  assert.ok(/\.footer\s*\{[^}]*justify-content:\s*space-between/.test(html));
});

test('static: citation from field only, never writer footnote', () => {
  const html = buildStaticHtml(
    { kind: 'claim', headline: 'h', support: 's', footnote: 'Hall & Kahan (2018). Med Clin North Am.' },
    CIT, true
  );
  assert.ok(html.includes(CIT));
  assert.ok(!html.includes('Hall &amp; Kahan'));
  const brand = buildStaticHtml({ kind: 'brand', headline: 'h', support: 's' }, CIT, true);
  assert.ok(!brand.includes(CIT));
});
