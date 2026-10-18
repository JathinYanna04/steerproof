"use client";

import React, { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { CheckCircle, Circle, Loader2, XCircle } from "lucide-react";
import { apiRequest, formatSeconds, showToast } from "@/lib/api";

type StageStatus = "DONE" | "RUNNING" | "FAILED" | "WAITING";

interface StageRun {
  stage: string;
  status: StageStatus;
  startedAt?: string | null;
  endedAt?: string | null;
  seconds?: number | null;
  model?: string | null;
  llmCalls?: number | null;
  cacheHits?: number | null;
  error?: string | null;
}

interface PaperStats {
  claimsTotal?: number;
  claimsAutoFlagged?: number;
  approved?: number;
  edited?: number;
  rejected?: number;
  pending?: number;
  needsEvidence?: number;
  reviewSecondsTotal?: number;
  humanSecondsTotal?: number;
  pipelineSeconds?: number | null;
  /** Sum of the latest PARSE/EXTRACT/VERIFY run durations, without gaps between retries */
  pipelineActiveSeconds?: number | null;
  writeRenderSeconds?: number | null;
  // Older field names, still sent by some backends
  claimsApproved?: number;
  claimsEdited?: number;
  claimsRejected?: number;
  doctorReviewSeconds?: number;
}

interface PaperDetail {
  paperId: string;
  title?: string | null;
  authors?: string | null;
  year?: number | null;
  journal?: string | null;
  doi?: string | null;
  status: string;
  failedStage?: string | null;
  error?: string | null;
  stages?: any[];
  stats?: PaperStats;
}

const AUTO_STAGES = ["PARSE", "EXTRACT", "VERIFY", "WRITE", "RENDER"] as const;

const STAGE_LABELS: Record<string, string> = {
  PARSE: "Parse (Docling layout)",
  EXTRACT: "Extract claims",
  VERIFY: "Verify quotes, numbers, entailment",
  CLAIM_REVIEW: "Gate 1: claim review",
  WRITE: "Write carousel, static, reel, caption",
  RENDER: "Render (Playwright, Remotion)",
  ASSET_REVIEW: "Gate 2: asset review",
  EXPORT: "Export package",
};

const ROWS = ["PARSE", "EXTRACT", "VERIFY", "CLAIM_REVIEW", "WRITE", "RENDER", "ASSET_REVIEW", "EXPORT"] as const;

const PROCESSING = new Set(["PARSING", "EXTRACTING", "VERIFYING", "WRITING", "RENDERING"]);

function secondsBetween(a?: string | null, b?: string | null): number | null {
  if (!a || !b) return null;
  const s = (new Date(b).getTime() - new Date(a).getTime()) / 1000;
  return s >= 0 ? s : null;
}

/** Latest run per stage from the raw run rows embedded in GET /api/papers/{id} (used when /runs is unavailable). */
function stagesFromRawRuns(raw: any[]): StageRun[] {
  return AUTO_STAGES.map((stage) => {
    const runs = raw.filter((r) => String(r.stage).toUpperCase() === stage);
    const r = runs[runs.length - 1];
    if (!r) return { stage, status: "WAITING" as StageStatus };
    const status: StageStatus = r.ok === true ? "DONE" : r.ok === false ? "FAILED" : r.endedAt ? "DONE" : "RUNNING";
    return {
      stage,
      status,
      startedAt: r.startedAt,
      endedAt: r.endedAt,
      seconds: r.seconds ?? secondsBetween(r.startedAt, r.endedAt),
      model: r.model ?? null,
      llmCalls: r.llmCalls,
      cacheHits: r.cacheHits,
      error: r.error ?? null,
    };
  });
}

function normalizeRun(r: any): StageRun {
  const raw = String(r.status || "").toUpperCase();
  const status: StageStatus =
    raw === "DONE" || raw === "OK" || raw === "SUCCESS"
      ? "DONE"
      : raw === "FAILED" || raw === "ERROR"
      ? "FAILED"
      : raw === "RUNNING"
      ? "RUNNING"
      : "WAITING";
  return { ...r, stage: String(r.stage).toUpperCase(), status, seconds: r.seconds ?? secondsBetween(r.startedAt, r.endedAt) };
}

function StatusIcon({ status }: { status: StageStatus | "ACTION" }) {
  if (status === "DONE") return <CheckCircle className="w-5 h-5 text-emerald-700" aria-label="done" />;
  if (status === "FAILED") return <XCircle className="w-5 h-5 text-red-700" aria-label="failed" />;
  if (status === "RUNNING") return <Loader2 className="w-5 h-5 text-rust animate-spin" aria-label="running" />;
  if (status === "ACTION") return <Circle className="w-5 h-5 text-rust fill-rust/30" aria-label="action required" />;
  return <Circle className="w-5 h-5 text-clay" aria-label="waiting" />;
}

export default function PaperPipelinePage() {
  const params = useParams();
  const paperId = params.id as string;

  const [paper, setPaper] = useState<PaperDetail | null>(null);
  const [stages, setStages] = useState<StageRun[] | null>(null);
  const [stagesSource, setStagesSource] = useState<"runs" | "paper" | null>(null);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const [retrying, setRetrying] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const fetchData = async () => {
    try {
      const p = await apiRequest<PaperDetail>(`/api/papers/${paperId}`, { silent: true });
      setPaper(p);
      setNotFound(false);
      try {
        const runs = await apiRequest<any[]>(`/api/papers/${paperId}/runs`, { silent: true });
        const byStage = new Map(runs.map((r) => [String(r.stage).toUpperCase(), normalizeRun(r)]));
        setStages(AUTO_STAGES.map((s) => byStage.get(s) ?? { stage: s, status: "WAITING" }));
        setStagesSource("runs");
      } catch {
        // Older backend without /runs: the same rows are embedded in the paper detail
        setStages(p.stages ? stagesFromRawRuns(p.stages) : null);
        setStagesSource(p.stages ? "paper" : null);
      }
      return p;
    } catch (e: any) {
      if (e?.status === 404) setNotFound(true);
      return null;
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    let cancelled = false;
    const loop = async () => {
      const p = await fetchData();
      if (cancelled) return;
      // Poll fast while the pipeline is working, slowly otherwise
      timer.current = setTimeout(loop, p && PROCESSING.has(p.status) ? 2000 : 15000);
    };
    loop();
    return () => {
      cancelled = true;
      if (timer.current) clearTimeout(timer.current);
    };
  }, [paperId]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleRetry = async () => {
    setRetrying(true);
    try {
      await apiRequest(`/api/papers/${paperId}/retry`, { method: "POST" });
      showToast("Stage restarted", "success");
      fetchData();
    } catch {
      // apiRequest already toasted the server error verbatim
    } finally {
      setRetrying(false);
    }
  };

  if (loading && !paper) {
    return <div className="max-w-4xl mx-auto px-4 py-16 text-center text-clay">Loading pipeline status...</div>;
  }
  if (notFound || !paper) {
    return <div className="max-w-4xl mx-auto px-4 py-16 text-center text-clay">Paper not found: {paperId}</div>;
  }

  const st = paper.stats || {};
  const approved = st.approved ?? st.claimsApproved ?? 0;
  const edited = st.edited ?? st.claimsEdited ?? 0;
  const rejected = st.rejected ?? st.claimsRejected ?? 0;
  const reviewSeconds = st.reviewSecondsTotal ?? st.doctorReviewSeconds ?? 0;

  const stageMap = new Map((stages || []).map((s) => [s.stage, s]));
  const status = paper.status;

  const gateRow = (key: string): { status: StageStatus | "ACTION"; text: string; route?: string } => {
    if (key === "CLAIM_REVIEW") {
      if (["WRITING", "RENDERING", "AWAITING_ASSET_REVIEW", "EXPORTED"].includes(status)) return { status: "DONE", text: "Passed", route: "claims" };
      if (status === "AWAITING_CLAIM_REVIEW") return { status: "ACTION", text: "Your review", route: "claims" };
      return { status: "WAITING", text: "Waiting", route: "claims" };
    }
    if (key === "ASSET_REVIEW") {
      if (status === "EXPORTED") return { status: "DONE", text: "Passed", route: "assets" };
      if (status === "AWAITING_ASSET_REVIEW") return { status: "ACTION", text: "Your review", route: "assets" };
      return { status: "WAITING", text: "Waiting", route: "assets" };
    }
    if (status === "EXPORTED") return { status: "DONE", text: "Exported", route: "export" };
    return { status: "WAITING", text: "Waiting", route: "export" };
  };

  return (
    <div className="max-w-4xl mx-auto px-4 py-8">
      <div className="mb-6">
        <div className="flex items-center gap-2 mb-2">
          <Link href="/" className="text-xs text-clay hover:text-rust">
            ← Papers
          </Link>
          <span className="text-xs text-clay">/</span>
          <span className="text-xs font-mono text-clay">{paper.paperId}</span>
          <span className="text-xs px-2.5 py-0.5 rounded-full bg-softPaper border border-blush font-medium text-ink" data-testid="paper-status">
            {status.replace(/_/g, " ").toLowerCase()}
          </span>
        </div>
        <h1 className="font-serif text-3xl text-ink mb-2">{paper.title || paper.paperId}</h1>
        <p className="text-sm text-clay">
          {[paper.authors, paper.year, paper.journal].filter(Boolean).join(" · ") || "Metadata not extracted"}
          {paper.doi ? ` · doi:${paper.doi}` : ""}
        </p>
      </div>

      <div className="flex flex-wrap gap-3 mb-8">
        <Link
          href={`/papers/${paperId}/claims`}
          className="px-4 py-2 bg-rust text-cream text-sm font-medium rounded-lg hover:bg-rust/90 transition-colors shadow-sm"
        >
          Review claims (Gate 1) →
        </Link>
        <Link
          href={`/papers/${paperId}/assets`}
          className="px-4 py-2 bg-softPaper border border-blush text-ink text-sm font-medium rounded-lg hover:bg-cream transition-colors"
        >
          Review assets (Gate 2) →
        </Link>
        <Link
          href={`/papers/${paperId}/export`}
          className="px-4 py-2 bg-softPaper border border-blush text-clay text-sm font-medium rounded-lg hover:text-ink hover:bg-cream transition-colors"
        >
          Export →
        </Link>
      </div>

      {/* Stats */}
      <div className="bg-softPaper border border-blush rounded-xl p-5 mb-8 shadow-sm" data-testid="stats-card">
        <h3 className="text-xs uppercase tracking-wider text-clay font-medium mb-3">Stats</h3>
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 text-center sm:text-left">
          <div>
            <div className="text-xl font-serif text-ink" data-testid="stat-claims-total">{st.claimsTotal ?? 0}</div>
            <div className="text-xs text-clay">Claims</div>
          </div>
          <div>
            <div className="text-xl font-serif text-amber-700">{st.claimsAutoFlagged ?? 0}</div>
            <div className="text-xs text-clay">Auto-flagged (amber, red, needs evidence)</div>
          </div>
          <div>
            <div className="text-xl font-serif text-ink">
              {approved} / {edited} / {rejected}
            </div>
            <div className="text-xs text-clay">Approved / edited / rejected</div>
          </div>
          <div>
            <div className="text-xl font-serif text-rust">{formatSeconds(reviewSeconds)}</div>
            <div className="text-xs text-clay">Claim review time</div>
          </div>
        </div>
        <div className="mt-4 pt-3 border-t border-blush/60 grid grid-cols-1 sm:grid-cols-3 gap-2 text-xs text-clay">
          <span>
            Pipeline (parse → verify, latest run):{" "}
            <strong className="text-ink" data-testid="stat-pipeline-seconds">
              {st.pipelineSeconds != null ? formatSeconds(st.pipelineSeconds) : "not recorded"}
            </strong>
            {st.pipelineActiveSeconds != null && st.pipelineActiveSeconds !== st.pipelineSeconds && (
              <> ({formatSeconds(st.pipelineActiveSeconds)} running)</>
            )}
          </span>
          <span>
            Write + render:{" "}
            <strong className="text-ink">{st.writeRenderSeconds != null ? formatSeconds(st.writeRenderSeconds) : "not recorded"}</strong>
          </span>
          <span>
            Total human time: <strong className="text-ink">{formatSeconds(st.humanSecondsTotal ?? 0)}</strong>
          </span>
        </div>
      </div>

      {status === "FAILED" && (
        <div className="bg-red-50 border border-red-300 rounded-xl p-4 mb-8 flex justify-between items-center gap-4">
          <div>
            <div className="text-xs font-bold text-red-900">Stage failed: {paper.failedStage || "unknown"}</div>
            <div className="text-sm text-red-800 mt-0.5 break-words">{paper.error || "No error message stored."}</div>
          </div>
          <button
            onClick={handleRetry}
            disabled={retrying}
            className="px-4 py-2 bg-red-800 text-cream text-xs font-medium rounded-md hover:bg-red-900 disabled:opacity-50"
          >
            {retrying ? "Retrying..." : "Retry stage"}
          </button>
        </div>
      )}

      <div className="bg-softPaper border border-blush rounded-xl p-6 shadow-sm">
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-xs uppercase tracking-wider text-clay font-medium">Stages</h3>
          {stagesSource === "paper" && <span className="text-[10px] text-clay">from run log (no /runs endpoint)</span>}
        </div>
        {!stages && <p className="text-xs text-clay mb-3">No runs recorded for this paper.</p>}
        <div className="space-y-3">
          {ROWS.map((key) => {
            const isAuto = (AUTO_STAGES as readonly string[]).includes(key);
            const run = isAuto ? stageMap.get(key) : undefined;
            const gate = isAuto ? null : gateRow(key);
            const rowStatus: StageStatus | "ACTION" = isAuto ? run?.status ?? "WAITING" : gate!.status;
            const text = isAuto
              ? rowStatus === "DONE"
                ? formatSeconds(run?.seconds ?? null)
                : rowStatus === "RUNNING"
                ? "Running"
                : rowStatus === "FAILED"
                ? "Failed"
                : "Waiting"
              : gate!.text;
            return (
              <div
                key={key}
                data-testid={`stage-${key}`}
                data-status={rowStatus}
                className="flex items-center justify-between p-3 rounded-lg border border-blush/60 bg-cream/50"
              >
                <div className="flex items-center gap-3 min-w-0">
                  <StatusIcon status={rowStatus} />
                  <div className="min-w-0">
                    <div className="text-sm font-medium text-ink flex flex-wrap items-center gap-2">
                      {STAGE_LABELS[key]}
                      {run?.model && (
                        <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-softPaper border border-blush text-clay" data-testid="stage-model">
                          {run.model}
                        </span>
                      )}
                    </div>
                    {run && (run.llmCalls != null || run.cacheHits != null) && rowStatus !== "WAITING" && (
                      <div className="text-[11px] text-clay mt-0.5">
                        {run.llmCalls ?? 0} LLM call(s) · {run.cacheHits ?? 0} cache hit(s)
                      </div>
                    )}
                    {run?.error && rowStatus === "FAILED" && (
                      <div className="text-xs text-red-700 mt-0.5 break-words">{run.error}</div>
                    )}
                  </div>
                </div>
                <div className="flex items-center gap-3 text-xs shrink-0">
                  <span
                    className={`font-mono ${
                      rowStatus === "DONE" ? "text-emerald-800 font-semibold" : rowStatus === "ACTION" ? "text-rust font-semibold" : "text-clay"
                    }`}
                  >
                    {text}
                  </span>
                  {gate?.route && (
                    <Link href={`/papers/${paperId}/${gate.route}`} className="text-xs text-rust hover:underline font-medium">
                      Open →
                    </Link>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
