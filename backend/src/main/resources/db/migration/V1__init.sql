-- V1__init.sql (PostgreSQL schema for SteerProof)

CREATE TABLE papers (
  id            TEXT PRIMARY KEY,                 -- P-0001
  title         TEXT,
  authors       TEXT,
  year          INT,
  journal       TEXT,
  doi           TEXT,
  source_url    TEXT,
  pdf_path      TEXT NOT NULL,
  theme         TEXT,                             -- optional operator hint, e.g. "weight regain"
  status        TEXT NOT NULL,                    -- see ARCHITECTURE state machine
  failed_stage  TEXT,
  error         TEXT,
  page_count    INT,
  batch_id      TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE passages (
  id          TEXT PRIMARY KEY,                   -- E-0001-0042
  paper_id    TEXT NOT NULL REFERENCES papers(id) ON DELETE CASCADE,
  section     TEXT,
  kind        TEXT NOT NULL,                      -- paragraph | table | caption | list | title
  page        INT NOT NULL,
  bbox        JSONB,                              -- {l,t,r,b} top-left origin, PDF points
  page_width  REAL,
  page_height REAL,
  text        TEXT NOT NULL,
  ord         INT NOT NULL
);
CREATE INDEX idx_passages_paper_ord ON passages(paper_id, ord);

CREATE TABLE claims (
  id                TEXT PRIMARY KEY,             -- C-0001-007
  paper_id          TEXT NOT NULL REFERENCES papers(id) ON DELETE CASCADE,
  passage_id        TEXT REFERENCES passages(id),
  quote             TEXT NOT NULL,
  scientific_claim  TEXT NOT NULL,
  consumer_draft    TEXT,
  numbers           JSONB NOT NULL DEFAULT '[]',  -- [{value:"10.0", unit:"%", context:"..."}]
  study_design      TEXT,
  population        TEXT,
  finding_class     TEXT NOT NULL,                -- PRIMARY | SECONDARY | EXPLORATORY | BACKGROUND
  limitations       JSONB NOT NULL DEFAULT '[]',
  quote_score       INT,                          -- rapidfuzz 0..100
  numbers_ok        BOOLEAN,
  entailment        TEXT,                         -- SUPPORTED | OVERCLAIM | UNSUPPORTED
  risk              TEXT,                         -- GREEN | AMBER | RED
  risk_reason       TEXT,
  population_fit    TEXT,                         -- HIGH | MEDIUM | LOW
  population_reason TEXT,
  doctor_status     TEXT NOT NULL,                -- PENDING | NEEDS_EVIDENCE | APPROVED | EDITED | REJECTED
  edited_copy       TEXT,
  doctor_name       TEXT,
  doctor_note       TEXT,
  review_seconds    INT NOT NULL DEFAULT 0,
  reviewed_at       TIMESTAMPTZ,
  is_seeded_bad     BOOLEAN NOT NULL DEFAULT false -- demo only
);
CREATE INDEX idx_claims_paper ON claims(paper_id);

CREATE TABLE assets (
  id          TEXT PRIMARY KEY,                   -- A-0001-CAR | A-0001-STA | A-0001-REEL | A-0001-CAP
  paper_id    TEXT NOT NULL REFERENCES papers(id) ON DELETE CASCADE,
  type        TEXT NOT NULL,                      -- CAROUSEL | STATIC | REEL | CAPTION
  language    TEXT NOT NULL DEFAULT 'en',         -- en | hinglish
  content     JSONB NOT NULL,                     -- writer output for this asset (see PROMPTS.md)
  render_paths JSONB NOT NULL DEFAULT '[]',
  status      TEXT NOT NULL,                      -- DRAFT | RENDERED | BLOCKED | STALE | APPROVED
  version     INT NOT NULL DEFAULT 1,
  approved_by TEXT,
  approved_at TIMESTAMPTZ,
  scheduled_for DATE
);

CREATE TABLE asset_claims (
  asset_id  TEXT REFERENCES assets(id) ON DELETE CASCADE,
  claim_id  TEXT REFERENCES claims(id),
  location  TEXT NOT NULL,                        -- 'slide:3', 'headline', 'scene:2', 'caption:p1'
  PRIMARY KEY (asset_id, claim_id, location)
);

CREATE TABLE policy_violations (
  id         BIGSERIAL PRIMARY KEY,
  asset_id   TEXT REFERENCES assets(id) ON DELETE CASCADE,
  claim_id   TEXT REFERENCES claims(id),
  location   TEXT,
  rule       TEXT NOT NULL,                       -- see GUARDRAILS.md rule codes
  severity   TEXT NOT NULL,                       -- BLOCKING | WARNING
  detail     TEXT NOT NULL,
  resolved   BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE pipeline_runs (
  id          BIGSERIAL PRIMARY KEY,
  paper_id    TEXT REFERENCES papers(id) ON DELETE CASCADE,
  stage       TEXT NOT NULL,
  started_at  TIMESTAMPTZ NOT NULL,
  ended_at    TIMESTAMPTZ,
  ok          BOOLEAN,
  llm_calls   INT DEFAULT 0,
  cache_hits  INT DEFAULT 0,
  error       TEXT
);

CREATE TABLE human_events (                       -- for honest human-time metrics
  id         BIGSERIAL PRIMARY KEY,
  paper_id   TEXT REFERENCES papers(id) ON DELETE CASCADE,
  actor      TEXT NOT NULL,
  kind       TEXT NOT NULL,                       -- UPLOAD | CLAIM_REVIEW | ASSET_REVIEW | EDIT | EXPORT
  seconds    INT NOT NULL,
  at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
  id         BIGSERIAL PRIMARY KEY,
  actor      TEXT NOT NULL,                       -- 'system' or doctor name
  action     TEXT NOT NULL,
  entity     TEXT NOT NULL,
  entity_id  TEXT NOT NULL,
  before     JSONB,
  after      JSONB,
  at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE batches (
  id         TEXT PRIMARY KEY,                    -- B-0001
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  week_start DATE
);
