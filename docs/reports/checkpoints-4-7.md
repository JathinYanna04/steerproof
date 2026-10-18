# SteerProof Build Report: Checkpoints 4–7

---

## SECTION A: FIXES FROM CHECKPOINT 3

### 1. Honest Comparison: Before & After Rewritten Drafts (Matched by Passage ID)
During the re-extraction run on paper `P-0001`, claim IDs were re-assigned. To provide a rigorous and honest comparison, the previously identified `OVERCLAIM` claims from Checkpoint 2 were retrieved from the system logs (step 1603 transcript) and matched directly by their source passage IDs (`E-0001-xxxx`) against the new extraction run.

| Passage ID | Quote Fragment | Old Consumer Draft (Checkpoint 2) | Old Entailment & Risk | New Consumer Draft (Rewritten) | New Entailment & Risk | Notes / Status |
|---|---|---|---|---|---|---|
| `E-0001-0010` (Page 1) | *"In a meta-analysis of 29 long-term weight loss studies, more than half of the lost weight was regained within two years..."* | *"Regaining weight after weight loss is common, with most people regaining over 80% within five years."* | **OVERCLAIM** (RED) | *"Across 29 long-term studies, participants on average regained over half their lost weight within two years."* | **SUPPORTED** (GREEN) | Removed aggregate-to-individual leap ("most people"). Correctly reflects study average. |
| `E-0001-0053` (Page 10) | *"In one study, patients entering a diet and exercise program expected to lose 20-40% of their starting body weight - amounts that can only realistically be achieved by bariatric surgery82."* | *"Many people starting lifestyle programs hope to lose 20-40% of their weight, which is rarely realistic without surgery."* | **OVERCLAIM** (RED) | *"Patients starting lifestyle programs often hoped to lose 20-40% of their weight, which exceeds typical non-surgical results."* | **OVERCLAIM** (RED) | Correctly retained as OVERCLAIM/RED because generalizing a single observational cohort into patient expectations remains clinically unsupported without qualifications. Rejected by reviewer. |
| `E-0001-0060` (Page 11) | *"In the Diabetes Prevention Program, 7% weight loss over six months led to 58% reduction in development of diabetes..."* | *"Losing just 7% of your body weight can lower diabetes risk by 58%, even if some weight returns."* | **OVERCLAIM** (RED) | *"In a prevention trial, losing 7% of body weight reduced the development of diabetes by 58%."* | **SUPPORTED** (AMBER) | Removed direct address to reader ("your body weight") and absolute promise ("can lower"). |
| `E-0001-0061` (Page 11) | *"In the Look Ahead trial, 6% weight loss over eight years yielded improvements in a range of cardiovascular risk factors..."* | *"Maintaining a 6% weight loss over eight years can improve blood sugar, cholesterol, and overall health outcomes."* | **OVERCLAIM** (RED) | *"In an eight-year trial, losing 6% of weight improved blood sugar, cholesterol, and reduced healthcare costs for participants."* | **SUPPORTED** (AMBER) | Removed direct personal promise ("can improve") and described outcomes observed in study participants. |

---

### 2. Stable Claim IDs & Demo Reset Endpoint
- **Stable Claim ID Invariant**: Enforced in `PipelineService.java`. Before executing `EXTRACT`, the service checks if any existing claims for the paper have been reviewed (`doctorStatus != PENDING || reviewedAt != null`). If reviewed claims exist, re-extraction is blocked to protect stable claim IDs from drift.
- **Paper Reset Endpoint**: Implemented `POST /api/papers/{id}/reset` in `PaperController.java` (gated by `DEMO_MODE=true` / `app.demo-mode=true`). It clears claims, reviews, human events, assets, policy violations, and re-run stages back to the post-parse baseline.

---

### 3. Reviewer Name Sanitization
- Removed all instances of fictional clinician names ("Dr. Aarav Patel") across PostgreSQL database tables (`claims`, `human_events`, `audit_log`).
- Set reviewer name to `"Jathin Reddy Yanna (non-clinician reviewer)"` for the P-0001 review records.
- Updated default backend reviewer fallback to `"Reviewer"` and unit tests to `"Test Reviewer"`.

---

### 4. Metrics Improvements
- **`claimsAutoFlagged`**: Now strictly computes count of claims where `risk == Risk.AMBER || risk == Risk.RED || doctorStatus == DoctorStatus.NEEDS_EVIDENCE`.
- **`pipelineSeconds`**: Now calculates duration of the latest successful end-to-end run only (`PARSE.startedAt` $\rightarrow$ latest `VERIFY.endedAt`).
- Verified with unit test `metricsCalculationClaimsAutoFlaggedAndPipelineSeconds` in `ReviewServiceTest.java`.

---

### 5. Audit Log JSON Object Representation
Updated `AuditService.java` to parse `before` and `after` JSON columns using `ObjectMapper.readTree` so that `GET /api/audit?entityId=...` returns structured JSON objects rather than escaped strings:
```json
[
  {
    "id": 27,
    "actor": "Jathin Reddy Yanna (non-clinician reviewer)",
    "action": "CLAIM_APPROVE",
    "entity": "claim",
    "entityId": "C-0001-001",
    "before": {
      "passageId": "E-0001-0010",
      "doctorName": null,
      "doctorNote": null,
      "editedCopy": null,
      "quoteScore": 100,
      "doctorStatus": "PENDING"
    },
    "after": {
      "passageId": "E-0001-0010",
      "doctorName": "Jathin Reddy Yanna (non-clinician reviewer)",
      "doctorNote": null,
      "editedCopy": null,
      "quoteScore": 100,
      "doctorStatus": "APPROVED"
    },
    "at": "2026-10-18T11:17:47.050172Z"
  }
]
```

---

## SECTION B: CHECKPOINT 4, WRITER + POLICY

### 1. Full Writer JSON for P-0001
The writer pipeline (`POST /write` in `ml-service` and stage `WRITE` in `backend`) generated the complete multi-format campaign for paper `P-0001`. Every text block specifies its `kind` (`claim`, `illustration`, `brand`) and explicitly lists all cited `claimIds`.

```json
{
  "coreMessage": "Long-term weight management is driven by appetite biology rather than willpower, making ongoing medical support essential for lasting results.",
  "reasoning": "Indian adults often internalize weight regain as personal failure, especially while navigating social family meals and high-stress desk jobs. By explaining the physiological appetite rebound and highlighting the value of sustained doctor-led care, this campaign shifts the narrative from shame to clinical credibility.",
  "carousel": [
    {
      "n": 1,
      "type": "hook",
      "headline": "Why does the weight come *back*?",
      "body": "It is easy to blame your willpower when old habits slip. But the science shows a different story.",
      "kind": "brand",
      "claimIds": [],
      "background": "cream"
    },
    {
      "n": 2,
      "type": "point",
      "headline": "Your body *fights* to regain.",
      "body": "For each kilogram lost, appetite increased by approximately 100 calories daily while resting burn dropped slightly.",
      "kind": "claim",
      "claimIds": ["C-0001-002"],
      "background": "cream"
    },
    {
      "n": 3,
      "type": "illustration",
      "headline": "The evening *chai* craving.",
      "body": "That sudden 5pm hunger at your desk is not weakness. Your biology is actively signaling for fuel after calorie deficits.",
      "kind": "illustration",
      "claimIds": [],
      "background": "cream"
    },
    {
      "n": 4,
      "type": "point",
      "headline": "Going *alone* is hard.",
      "body": "Across 29 long-term studies, participants on average regained over half their lost weight within two years.",
      "kind": "claim",
      "claimIds": ["C-0001-001"],
      "background": "maroon"
    },
    {
      "n": 5,
      "type": "point",
      "headline": "Continuous *support* changes outcomes.",
      "body": "Ongoing support from healthcare professionals or groups helped people maintain weight loss better than short-term programs alone.",
      "kind": "claim",
      "claimIds": ["C-0001-003"],
      "background": "cream"
    },
    {
      "n": 6,
      "type": "source",
      "headline": "Backed by *medical* research.",
      "body": "Hall & Kahan (2018). Maintenance of lost weight and long-term management of obesity. Med Clin North Am.",
      "kind": "brand",
      "claimIds": [],
      "background": "cream"
    },
    {
      "n": 7,
      "type": "closing",
      "headline": "Clinical care, *doctor-led*.",
      "body": "Metabolic care, calibrated for Indian biology. Questions? Ask a Steer doctor. steer · steerclub.com",
      "kind": "brand",
      "claimIds": [],
      "background": "cream"
    }
  ],
  "static": {
    "headline": "Weight regain is *biology*.",
    "support": "For each kilogram lost, appetite increased by approximately 100 calories daily.",
    "kind": "claim",
    "claimIds": ["C-0001-002"],
    "footnote": "Hall & Kahan (2018). Med Clin North Am. steerclub.com"
  },
  "reel": [
    {
      "n": 1,
      "motion": "title",
      "onScreen": "Why does the weight return?",
      "voiceover": "Ever wonder why keeping weight off feels like an uphill battle after every diet? It is not your willpower.",
      "kind": "brand",
      "claimIds": [],
      "brollQuery": "steaming black tea cup"
    },
    {
      "n": 2,
      "motion": "stat",
      "onScreen": "Your appetite increases",
      "voiceover": "For each kilogram lost, appetite increased by approximately 100 calories daily while resting burn dropped slightly in clinical studies.",
      "kind": "claim",
      "claimIds": ["C-0001-002"],
      "brollQuery": "fresh roti on plate"
    },
    {
      "n": 3,
      "motion": "stat",
      "onScreen": "Long-term study findings",
      "voiceover": "Across 29 long-term studies, participants on average regained over half their lost weight within two years.",
      "kind": "claim",
      "claimIds": ["C-0001-001"],
      "brollQuery": "medical journal pages open"
    },
    {
      "n": 4,
      "motion": "quote",
      "onScreen": "Doctor support changes things",
      "voiceover": "Ongoing support from healthcare professionals or groups helped people maintain weight loss better than short-term programs alone.",
      "kind": "claim",
      "claimIds": ["C-0001-003"],
      "brollQuery": "stethoscope on wooden desk"
    },
    {
      "n": 5,
      "motion": "closing",
      "onScreen": "steer · steerclub.com",
      "voiceover": "Metabolic care, calibrated for Indian biology. Stop Googling. Ask a doctor at steerclub.com.",
      "kind": "brand",
      "claimIds": [],
      "brollQuery": "minimalist medical clinic desk"
    }
  ],
  "caption": {
    "paragraphs": [
      {
        "kind": "illustration",
        "text": "Ever feel like your appetite spikes the moment you lose weight? That 4pm hunger during a busy workday is not a personal failure. It is basic biology.",
        "claimIds": []
      },
      {
        "kind": "claim",
        "text": "Research shows that for each kilogram lost, appetite increased by approximately 100 calories daily while resting burn dropped slightly. Across 29 long-term studies, participants on average regained over half their lost weight within two years.",
        "claimIds": ["C-0001-001", "C-0001-002"]
      },
      {
        "kind": "claim",
        "text": "Ongoing support from healthcare professionals or groups helped people maintain weight loss better than short-term programs alone. Sustainable metabolic health requires a planned strategy, not crash diets.",
        "claimIds": ["C-0001-003"]
      },
      {
        "kind": "brand",
        "text": "Clinical weight loss, doctor-led. Stop Googling. Ask a doctor. steerclub.com",
        "claimIds": []
      }
    ],
    "hashtags": [
      "#steerclub",
      "#metabolichealth",
      "#weightcare",
      "#doctorled",
      "#healthindia",
      "#evidencebased",
      "#indianwellness",
      "#metabolism",
      "#clinicalcare"
    ]
  }
}
```

---

### 2. Injected Bad Asset Policy Test
We created a test asset containing the string `"Lose 5 kg in 2 weeks, guaranteed! OZEMPIC"` and evaluated it through `PolicyChecker`:

```
=== Injected Bad Asset PolicyChecker Violations ===
Violation: rule=DRUG_BRAND, severity=BLOCKING, location=static:headline, detail=Mention of drug brand: ozempic
Violation: rule=PROMISE, severity=BLOCKING, location=static:headline, detail=Promise found: Lose 5 kg
Violation: rule=PROMISE, severity=BLOCKING, location=static:headline, detail=Promise found: in 2 weeks
Violation: rule=PROMISE, severity=BLOCKING, location=static:headline, detail=Promise found: guaranteed
Violation: rule=HYPE, severity=BLOCKING, location=static:headline, detail=Hype phrase: guaranteed
Violation: rule=ALL_CAPS, severity=BLOCKING, location=static:headline, detail=All-caps token: OZEMPIC
Asset Status: BLOCKED
```

**Result**: `DRUG_BRAND`, `PROMISE`, `HYPE`, and `ALL_CAPS` all fired as `BLOCKING` violations, and the asset was correctly placed into status `BLOCKED`.

---

### 3. PolicyChecker Unit Test Suite
Ran `PolicyCheckerTest` covering all 15 deterministic guardrail rules from `docs/GUARDRAILS.md`:
```
[INFO] Running com.steerclub.steerproof.policy.PolicyCheckerTest
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.666 s -- in com.steerclub.steerproof.policy.PolicyCheckerTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] BUILD SUCCESS
```

Rule Coverage Verification:
1. `DRUG_BRAND` (`testDrugBrandRule`): PASSED
2. `DRUG_GENERIC_PROMO` (`testDrugGenericPromoRule`): PASSED
3. `PROMISE` (`testPromiseRule`): PASSED
4. `HYPE` (`testHypeRule`): PASSED
5. `FEAR_SHAME` (`testFearShameRule`): PASSED
6. `BODY` (`testBodyRule`): PASSED
7. `ALL_CAPS` (`testAllCapsRule`): PASSED
8. `LENGTH` (`testLengthRule`): PASSED
9. `UNCITED_CLAIM` (`testUncitedClaimRule`): PASSED
10. `NUMBER_MISMATCH` (`testNumberMismatchRule`): PASSED
11. `ILLUSTRATION_WITH_NUMBER` (`testIllustrationWithNumberRule`): PASSED
12. `CERTAINTY_ON_EXPLORATORY` (`testCertaintyOnExploratoryRule`): PASSED
13. `CAUSAL_ON_ASSOCIATION` (`testCausalOnAssociationRule`): PASSED
14. `MISSING_SIGNOFF` (`testMissingSignoffRule`): PASSED
15. `EMOJI_IN_ASSET` (`testEmojiInAssetRule`): PASSED
16. Injected Bad Asset (`testInjectedBadAssetFiresAllTargetRulesAndBlocks`): PASSED

---

## SECTION C: CHECKPOINT 5, CAROUSEL + STATIC

### 1. Generated Render File Paths
Rendered at 1080×1350 via Playwright using Chromium (`/usr/bin/chromium`), custom Google web fonts, Steer brand design tokens, and diagonal draft watermark:
- `storage/renders/P-0001/carousel/1.png` (Hook slide: "Why does the weight come *back*?", cream background `#FBF8F3`)
- `storage/renders/P-0001/carousel/2.png` (Point slide: "Your body *fights* to regain.", cream background `#FBF8F3`)
- `storage/renders/P-0001/carousel/3.png` (Illustration slide: "The evening *chai* craving.", cream background `#FBF8F3`)
- `storage/renders/P-0001/carousel/4.png` (Point slide: "Going *alone* is hard.", maroon background `#3D141A`, blush text `#E6CBBD`)
- `storage/renders/P-0001/carousel/5.png` (Point slide: "Continuous *support* changes outcomes.", cream background `#FBF8F3`)
- `storage/renders/P-0001/carousel/6.png` (Source slide: "Backed by *medical* research.", cream background `#FBF8F3`)
- `storage/renders/P-0001/carousel/7.png` (Closing slide: "Clinical care, *doctor-led*.", steer · steerclub.com signoff)
- `storage/renders/P-0001/static.png` (1080×1350 single-image quote card: "Weight regain is *biology*.")

### 2. Block Bounding Box JSON

#### Slide 2 (`renders/P-0001/carousel/2.png`):
```json
[
  {
    "slide": 2,
    "blockId": "slide2.label",
    "x": 96,
    "y": 120,
    "w": 888,
    "h": 29
  },
  {
    "slide": 2,
    "blockId": "slide2.headline",
    "x": 96,
    "y": 189,
    "w": 888,
    "h": 92
  },
  {
    "slide": 2,
    "blockId": "slide2.body",
    "x": 96,
    "y": 305,
    "w": 888,
    "h": 151
  },
  {
    "slide": 2,
    "blockId": "slide2.footer",
    "x": 96,
    "y": 1166,
    "w": 888,
    "h": 44
  }
]
```

#### Static (`renders/P-0001/static.png`):
```json
[
  {
    "blockId": "static.label",
    "x": 96,
    "y": 120,
    "w": 888,
    "h": 29
  },
  {
    "blockId": "static.headline",
    "x": 96,
    "y": 189,
    "w": 888,
    "h": 97
  },
  {
    "blockId": "static.support",
    "x": 96,
    "y": 326,
    "w": 888,
    "h": 204
  },
  {
    "blockId": "static.footnote",
    "x": 96,
    "y": 562,
    "w": 888,
    "h": 31
  },
  {
    "blockId": "static.footer",
    "x": 96,
    "y": 1166,
    "w": 888,
    "h": 44
  }
]
```

### 3. Visual Check Confirmation
- **Watermark**: Fixed diagonal watermark `draft · pending doctor review` clearly visible across the viewport at subtle opacity (4.5%), rotated -28deg, repeating, pointer-events none.
- **Typography**:
  - `Instrument Serif` (regular and italic) for editorial headlines with asterisks rendered as elegant italic accents (`<em>`).
  - `Inter` (regular, medium, bold) for clean, readable body text and category pill labels.
  - `Caveat` handwriting script for warm handwritten margin callouts (`.handwritten`).
- **Color Palettes**:
  - Cream palette (`background: #FBF8F3`, primary text `#3D141A`, secondary text `#6E5D57`, accents `#C04E2A`) on slides 1, 2, 3, 5, 6, 7 and static.
  - Maroon palette (`background: #3D141A`, primary text `#FBF8F3`, secondary text `#E6CBBD`, card `#4E1A22`) on slide 4 for contrast and visual rhythm.
- **Signoff & Footnotes**:
  - `steer · steerclub.com` branding and paper citation (`Hall & Kahan (2018)`) positioned at footer on slide 7 and static.
- **Pipeline Integration**:
  - Pipeline Stage 5 `RENDER` automatically calls `RenderServiceClient`, saving paths to `assets.render_paths` in PostgreSQL and transitioning paper status to `AWAITING_ASSET_REVIEW`.


