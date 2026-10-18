-- Additive only: the model (or tool) that ran each pipeline stage, for the pipeline page.
ALTER TABLE pipeline_runs ADD COLUMN IF NOT EXISTS model TEXT;
