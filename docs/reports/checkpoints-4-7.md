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
