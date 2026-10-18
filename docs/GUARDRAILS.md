# Guardrails

Two layers:
1. **Deterministic** (`backend/policy/PolicyChecker.java`), run on every writer output string. These rules are fast, explainable, and testable.
2. **Semantic** (the ml-service verifier), which flags risk on claims before the doctor sees them.

Neither layer replaces the doctor. They make review faster and catch obvious failures.

## Deterministic rules

| Code | Severity | Check |
|---|---|---|
| `DRUG_BRAND` | BLOCKING | Case-insensitive word match: ozempic, wegovy, rybelsus, mounjaro, zepbound, saxenda, victoza, trulicity, orlistat brand names (xenical, alli), contrave, qsymia, metformin brand names (glycomet, glucophage) |
| `DRUG_GENERIC_PROMO` | WARNING | semaglutide, tirzepatide, liraglutide, dulaglutide appear in copy. Allowed only in a `claim` block whose claim text contains the same word. Flag for the doctor |
| `PROMISE` | BLOCKING | Regex: `\b(lose|drop|shed)\s+\d+\s*(kg|kilo|kilos|pounds|lbs)\b`, `\bin\s+\d+\s*(days|weeks)\b` near lose/weight, "guarantee(d)", "results in", "you will lose" |
| `HYPE` | BLOCKING | miracle, magic, secret, hack, cure(s), melt (fat), detox, burn fat fast, revolutionary, breakthrough, instantly, effortless |
| `FEAR_SHAME` | BLOCKING | lazy, fat people, obese people (person-first required), ugly, disgusting, "no excuses", "you're killing", "ticking time bomb", deadly |
| `BODY` | BLOCKING | before and after, before/after, bikini body, beach body, transformation (in a body sense), flat belly |
| `ALL_CAPS` | BLOCKING | any token of 4+ letters in all caps, except allowlist: GLP-1, HbA1c, BMI, ICMR, INDIAB, DNA, RCT, STEP, SURMOUNT, PCOS, WHO, ICMR-INDIAB |
| `LENGTH` | BLOCKING | over the limits in `docs/BRAND.md › Copy limits` |
| `UNCITED_CLAIM` | BLOCKING | block `kind=claim` with empty `claimIds`, or a claimId not APPROVED/EDITED |
| `NUMBER_MISMATCH` | BLOCKING | a number in a block is not present in any linked claim's quote |
| `ILLUSTRATION_WITH_NUMBER` | BLOCKING | an `illustration` block contains a percentage or statistic |
| `CERTAINTY_ON_EXPLORATORY` | WARNING | a block linked to an EXPLORATORY claim contains: proves, always, never, will, definitely, shows that, and lacks a qualifier (suggests, may, in this study, in the trial, one study) |
| `CAUSAL_ON_ASSOCIATION` | WARNING | the linked claim text contains "associated"/"linked"/"correlated", but the block uses causes/leads to/makes you/because of |
| `MISSING_SIGNOFF` | BLOCKING | the last carousel slide, last reel scene, or caption does not contain "steerclub.com" |
| `EMOJI_IN_ASSET` | WARNING | emoji in slide/static/onScreen text (allowed in caption) |

Implementation notes:
- Keep the rules in `src/main/resources/policy/rules.yaml` (word lists + regex + severity) so they can be edited without code.
- Each violation records `location` (`slide:3`, `scene:2`, `caption:p2`, `static:headline`) and `detail` (the matched text).
- A `BLOCKING` violation sets the asset to `BLOCKED`. The doctor can see it, but approval is refused until the asset is regenerated or the copy is edited.
- `WARNING` is shown in yellow, and the doctor may approve anyway (the override is recorded in the audit log).

## B-roll denylist (before Pexels search)
Reject queries containing: body, belly, waist, fat, obese, scale, weighing, measuring tape, gym, workout, bikini, abs, diet pills, injection, syringe, pills, hospital bed, surgery, doctor (avoid implying a real Steer doctor).
Prefer an allowlist pattern: food (roti, rice, dal, chai, thali, vegetables, fruit), places (kitchen, office desk, park, street, market), objects (tea cup, clock, notebook, stethoscope on table), nature (sunrise, walking path).

## Risk rubric (`ml-service/app/config/risk_rubric.yaml`)

```yaml
GREEN:
  - Primary or secondary outcome of an RCT or large cohort, stated with its qualifiers
  - Well-established mechanism described in a review (e.g. muscle helps glucose uptake)
  - Simplification keeps population, direction and magnitude
AMBER:
  - Exploratory, post hoc, extension or subgroup finding
  - Observational association that a reader may take as cause
  - Population differs from target audience (non-Asian, single sex, specific disease)
  - Involves medicines (even generic names): keep strictly educational
  - Numbers are relative (%), and absolute values are not stated
RED:
  - Not supported by the quote, or stronger than it
  - Could be read as advice to start/stop/change a medicine or dose
  - Animal, in-vitro, or very small studies (n < 30) presented as applying to people
  - Anything implying a weight-loss amount or timeline for the reader
```

## Target population profile (`population_profile` in the verifier)

```
Steer's audience: adults in India, mostly 25–55, urban and semi-urban, many with
overweight, abdominal obesity, prediabetes, type 2 diabetes, PCOS or fatty liver.
Common traits: South Asian "thin-fat" phenotype (more visceral fat and insulin
resistance at lower BMI), carbohydrate-heavy diets (rice, roti), sedentary desk jobs,
family-centred eating. Indian or South Asian study data counts as a HIGH fit.
```

## Regulatory note (for the README / Loom, not legal advice)
The pipeline is built to avoid promoting prescription drugs and to avoid miracle or exaggerated claims, in line with the spirit of India's Drugs and Magic Remedies Act and ASCI health-advertising guidance. It is a safety net, not a compliance certification. A qualified reviewer must still approve every post.
