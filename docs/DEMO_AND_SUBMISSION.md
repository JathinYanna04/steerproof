# Demo and submission

## Papers (open access; verify the full text is downloadable before use)
| Use | Paper | Why |
|---|---|---|
| Carousel | Hall KD, Kahan S. *Maintenance of Lost Weight and Long-Term Management of Obesity.* Med Clin North Am. 2018. (PMC5764193) | "Will I gain it back?" Biology, not willpower: a perfect Steer worry to answer |
| Reel + blocked-claim demo | Wilding JPH et al. *Weight regain and cardiometabolic effects after withdrawal of semaglutide: the STEP 1 trial extension.* Diabetes Obes Metab. 2022. (PMC9542252) | "Planned exit" message; exploratory finding, so it is the ideal guardrail demo. Copy must not promote the drug: frame it as "after treatment and lifestyle support stopped" |
| Static | An open-access paper on muscle/protein during weight loss, or on Indian/South Asian metabolic risk at lower BMI | "Muscle matters" or "built for Indian biology." Confirm open access first; replace it if the full text is not free |

Keep `demo/papers/` with the PDFs and `demo/papers/SOURCES.md` with the citation + URL + license note for each.

## Per-post deliverable (judges asked for this)
For each finished post, include in the submission:
- **Message:** the `coreMessage`
- **Reasoning:** the writer's `reasoning`, edited by you if needed
- **Audience:** who it is for
- **Sources:** the claims table (claim → quote → page)
- **Human time:** measured from `human_events`

## Metrics to report (from `/api/metrics.csv`)
| Metric | Meaning |
|---|---|
| Pipeline seconds | Ingest → rendered previews, no humans |
| LLM calls / cache hits | Proves reruns are free |
| Claims extracted / auto-flagged (AMBER+RED+NEEDS_EVIDENCE) | |
| Claims edited / rejected by reviewer | Honest accuracy signal |
| Review seconds (claims + assets) | |
| Total human time per paper | Upload + reviews + export clicks |

State honestly: "Reviews in this demo were done by a non-clinician team member. A Steer doctor's review time will differ."

## 2-minute Loom script
| Time | Show | Say |
|---|---|---|
| 0:00–0:12 | A dense PDF next to a finished carousel | "Steer has papers full of answers people need. Turning one into a post takes hours, and the doctor has to check every claim from scratch." |
| 0:12–0:30 | Upload, stage stepper running | "One upload. Docling maps every passage on every page. Gemini extracts claims, but it has to cite a passage ID, and our code verifies the quote. The AI never decides where a claim came from." |
| 0:30–0:55 | Claims console: risk lights, population fit, page highlight; approve with keyboard | "The doctor sees each claim beside its exact source, already risk-scored for Indian patients. Approve, edit, reject. Seconds per claim." |
| 0:55–1:20 | Seeded bad claim: RED → asset STALE → export 422 → reject → regenerate → export works | "Here's 'weight always comes back fully.' The verifier flags it as overclaiming an exploratory finding. Even if it slips through, export is blocked until it's fixed." |
| 1:20–1:45 | Carousel, static, reel playing; click a slide line → source opens | "Every sentence on every format clicks back to the paper. Indian-life examples are marked as illustrations, never as findings." |
| 1:45–2:00 | Stats card + batch week view | "Human time per paper: X minutes. Five papers in, a week of content out. All on free tools." |

## Submission checklist
- [ ] 3 finished posts (carousel, static, reel) as files + caption text
- [ ] Message + reasoning for each post
- [ ] Bonus: extra posts, Hinglish version, batch week plan
- [ ] Pipeline doc: architecture diagram (from docs/ARCHITECTURE.md), tools list, prompts (docs/PROMPTS.md), guardrails
- [ ] Recorded run from paper to posts (the full screen recording, separate from the Loom)
- [ ] Human-effort table per paper
- [ ] 2-minute Loom
- [ ] GitHub repo link with README
- [ ] Disclosure: team members, AI tools used (Gemini, ChatGPT, Grok, Kimi, Claude, Antigravity, Claude Code), libraries, Pexels credits, music credits
- [ ] PS1 submitted separately (both problem statements are required)
