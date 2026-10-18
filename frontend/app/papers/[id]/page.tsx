"use client";

import React, { useEffect, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { apiRequest, showToast } from "@/lib/api";

interface PaperDetail {
  id: string;
  title?: string;
  authors?: string;
  year?: number;
  journal?: string;
  doi?: string;
  status: string;
  failedStage?: string;
  error?: string;
  pageCount?: number;
  passageCount?: number;
}

interface PipelineRun {
  id: number;
  stage: string;
  startedAt: string;
  endedAt?: string;
  ok?: boolean;
  error?: string;
  llmCalls?: number;
  cacheHits?: number;
}

interface ClaimItem {
  id: string;
  doctorStatus: string;
  risk?: string;
  reviewSeconds?: number;
}

const STAGES = [
  { key: "PARSE", label: "Parsing (Docling PDF Layout)", model: null, reviewRoute: null },
  { key: "EXTRACT", label: "Extracting (Clinical Claims)", model: "gemini-3.7-flash", reviewRoute: null },
  { key: "VERIFY", label: "Verifying (Quote Matching & Entailment)", model: "gemini-3.7-flash", reviewRoute: null },
  { key: "CLAIM_REVIEW", label: "Claim Review (Gate 1)", model: "Human Clinician", reviewRoute: "claims" },
  { key: "WRITE", label: "Writing (Creative Assets & Voice)", model: "gemini-3.7-flash", reviewRoute: null },
  { key: "RENDER", label: "Rendering (Playwright & Remotion)", model: null, reviewRoute: null },
  { key: "ASSET_REVIEW", label: "Asset Review (Gate 2)", model: "Human Clinician", reviewRoute: "assets" },
  { key: "EXPORT", label: "Export Package (ZIP Bundle)", model: null, reviewRoute: "export" },
];

export default function PaperPipelinePage() {
  const params = useParams();
  const router = useRouter();
  const paperId = params.id as string;

  const [paper, setPaper] = useState<PaperDetail | null>(null);
  const [runs, setRuns] = useState<PipelineRun[]>([]);
  const [claims, setClaims] = useState<ClaimItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [retrying, setRetrying] = useState(false);

  useEffect(() => {
    fetchData();
    const interval = setInterval(() => {
      fetchData(false);
    }, 2000);
    return () => clearInterval(interval);
  }, [paperId]);

  const fetchData = async (showLoading = true) => {
    if (showLoading && !paper) setLoading(true);
    try {
      const p = await apiRequest<PaperDetail>(`/api/papers/${paperId}`);
      setPaper(p);

      const r = await apiRequest<PipelineRun[]>(`/api/papers/${paperId}/runs`).catch(() => []);
      setRuns(r);

      const c = await apiRequest<ClaimItem[]>(`/api/papers/${paperId}/claims`).catch(() => []);
      setClaims(c);
    } catch (e) {
      console.error(e);
    } finally {
      if (showLoading) setLoading(false);
    }
  };

  const handleRetry = async () => {
    setRetrying(true);
    try {
      await apiRequest(`/api/papers/${paperId}/retry`, { method: "POST" });
      showToast("Pipeline stage restarted!", "success");
      fetchData(false);
    } catch (err: any) {
      showToast(err.message || "Retry failed", "error");
    } finally {
      setRetrying(false);
    }
  };

  if (loading && !paper) {
    return <div className="max-w-4xl mx-auto px-4 py-16 text-center text-clay">Loading pipeline status...</div>;
  }

  if (!paper) {
    return <div className="max-w-4xl mx-auto px-4 py-16 text-center text-clay">Paper not found: {paperId}</div>;
  }

  // Calculate statistics
  const claimsTotal = claims.length;
  const claimsFlagged = claims.filter((c) => c.risk === "AMBER" || c.risk === "RED" || c.doctorStatus === "NEEDS_EVIDENCE").length;
  const claimsApproved = claims.filter((c) => c.doctorStatus === "APPROVED").length;
  const claimsEdited = claims.filter((c) => c.doctorStatus === "EDITED").length;
  const claimsRejected = claims.filter((c) => c.doctorStatus === "REJECTED").length;
  const reviewSeconds = claims.reduce((acc, c) => acc + (c.reviewSeconds || 0), 0);

  // Pipeline execution time
  let pipelineTimeSec = 0;
  runs.forEach((r) => {
    if (r.startedAt && r.endedAt) {
      const dur = Math.round((new Date(r.endedAt).getTime() - new Date(r.startedAt).getTime()) / 1000);
      if (dur > 0) pipelineTimeSec += dur;
    }
  });

  const formatSeconds = (sec: number) => {
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return m > 0 ? `${m}m ${s}s` : `${s}s`;
  };

  const getStageStatus = (stageKey: string) => {
    // Find matching run
    const run = runs.find((r) => r.stage.toUpperCase() === stageKey);

    // Human Gate 1
    if (stageKey === "CLAIM_REVIEW") {
      if (["WRITING", "RENDERING", "AWAITING_ASSET_REVIEW", "EXPORTED"].includes(paper.status)) {
        return { state: "DONE", text: "Completed", icon: "✓" };
      }
      if (paper.status === "AWAITING_CLAIM_REVIEW") {
        return { state: "ACTIVE", text: "Action Required", icon: "●" };
      }
      return { state: "PENDING", text: "Pending", icon: "○" };
    }

    // Human Gate 2
    if (stageKey === "ASSET_REVIEW") {
      if (paper.status === "EXPORTED") {
        return { state: "DONE", text: "Approved", icon: "✓" };
      }
      if (paper.status === "AWAITING_ASSET_REVIEW") {
        return { state: "ACTIVE", text: "Action Required", icon: "●" };
      }
      return { state: "PENDING", text: "Pending", icon: "○" };
    }

    // Export
    if (stageKey === "EXPORT") {
      if (paper.status === "EXPORTED") {
        return { state: "DONE", text: "Exported", icon: "✓" };
      }
      return { state: "PENDING", text: "Pending", icon: "○" };
    }

    // Automatic runs
    if (run) {
      if (run.ok === true) {
        const sec = run.endedAt && run.startedAt
          ? Math.max(1, Math.round((new Date(run.endedAt).getTime() - new Date(run.startedAt).getTime()) / 1000))
          : 0;
        return { state: "DONE", text: `${sec}s`, icon: "✓", run };
      }
      if (run.ok === false) {
        return { state: "FAILED", text: "Failed", icon: "✗", run };
      }
      return { state: "RUNNING", text: "Running...", icon: "⏳", run };
    }

    // Current paper status matches
    if (paper.status === stageKey || (stageKey === "PARSE" && paper.status === "PARSING")) {
      return { state: "RUNNING", text: "Processing...", icon: "⏳" };
    }

    return { state: "PENDING", text: "Waiting", icon: "○" };
  };

  return (
    <div className="max-w-4xl mx-auto px-4 py-8">
      {/* Paper Metadata Header */}
      <div className="mb-6">
        <div className="flex items-center gap-2 mb-2">
          <Link href="/" className="text-xs text-clay hover:text-rust">← Papers</Link>
          <span className="text-xs text-clay">/</span>
          <span className="text-xs font-mono text-clay">{paper.id}</span>
          <span className="text-xs px-2.5 py-0.5 rounded-full bg-softPaper border border-blush font-medium text-ink">
            {paper.status.replace(/_/g, " ")}
          </span>
        </div>
        <h1 className="font-serif text-3xl font-normal text-ink mb-2">
          {paper.title || "Clinical Paper Processing"}
        </h1>
        <p className="text-sm text-clay">
          {paper.authors || "Authors"} {paper.year ? `(${paper.year})` : ""} {paper.journal ? `· ${paper.journal}` : ""}
          {paper.pageCount ? ` · ${paper.pageCount} pages` : ""}
          {paper.passageCount ? ` · ${paper.passageCount} passages` : ""}
        </p>
      </div>

      {/* Action Buttons */}
      <div className="flex flex-wrap gap-3 mb-8">
        <Link
          href={`/papers/${paperId}/claims`}
          className="px-4 py-2 bg-rust text-cream text-sm font-medium rounded-lg hover:bg-[#A84222] transition-colors flex items-center gap-2 shadow-sm"
        >
          Review Claims (Gate 1) →
        </Link>
        <Link
          href={`/papers/${paperId}/assets`}
          className="px-4 py-2 bg-softPaper border border-blush text-ink text-sm font-medium rounded-lg hover:bg-cream transition-colors flex items-center gap-2"
        >
          Review Assets (Gate 2) →
        </Link>
        <Link
          href={`/papers/${paperId}/export`}
          className="px-4 py-2 bg-softPaper border border-blush text-clay text-sm font-medium rounded-lg hover:text-ink hover:bg-cream transition-colors flex items-center gap-2"
        >
          Export Package →
        </Link>
      </div>

      {/* Stats Card */}
      <div className="bg-softPaper border border-blush rounded-xl p-5 mb-8 shadow-sm">
        <h3 className="text-xs uppercase tracking-wider text-clay font-medium mb-3">Live Clinical Pipeline Metrics</h3>
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 text-center sm:text-left">
          <div>
            <div className="text-xl font-serif text-ink">{claimsTotal}</div>
            <div className="text-xs text-clay">Total Claims</div>
          </div>
          <div>
            <div className="text-xl font-serif text-amber-700">{claimsFlagged}</div>
            <div className="text-xs text-clay">Auto-Flagged (Amber/Red)</div>
          </div>
          <div>
            <div className="text-xl font-serif text-emerald-800">{claimsApproved} / {claimsEdited} / {claimsRejected}</div>
            <div className="text-xs text-clay">Approved / Edited / Rejected</div>
          </div>
          <div>
            <div className="text-xl font-serif text-rust">{formatSeconds(reviewSeconds)}</div>
            <div className="text-xs text-clay">Clinician Review Time</div>
          </div>
        </div>
        <div className="mt-3 pt-3 border-t border-blush/60 flex justify-between text-xs text-clay">
          <span>Automated Pipeline Time: <strong className="text-ink">{formatSeconds(pipelineTimeSec)}</strong></span>
          <span>Demographic Calibration: <strong className="text-ink">Indian Metabolic Biology</strong></span>
        </div>
      </div>

      {/* Error / Retry Banner */}
      {paper.status === "FAILED" && (
        <div className="bg-red-950/10 border border-red-300 rounded-xl p-4 mb-8 flex justify-between items-center gap-4">
          <div>
            <div className="text-xs font-bold text-red-900 uppercase">Stage Failure: {paper.failedStage || "Unknown"}</div>
            <div className="text-sm text-red-800 mt-0.5">{paper.error || "An error occurred during stage execution."}</div>
          </div>
          <button
            onClick={handleRetry}
            disabled={retrying}
            className="px-4 py-2 bg-red-800 text-cream text-xs font-medium rounded-md hover:bg-red-900 disabled:opacity-50"
          >
            {retrying ? "Retrying..." : "Retry Stage"}
          </button>
        </div>
      )}

      {/* Vertical Stage Stepper */}
      <div className="bg-softPaper border border-blush rounded-xl p-6 shadow-sm">
        <h3 className="text-xs uppercase tracking-wider text-clay font-medium mb-4">Pipeline Execution Stages</h3>
        <div className="space-y-4">
          {STAGES.map((s, idx) => {
            const st = getStageStatus(s.key);
            return (
              <div
                key={s.key}
                className="flex items-center justify-between p-3 rounded-lg border border-blush/60 bg-cream/50 hover:bg-cream transition-colors"
              >
                <div className="flex items-center gap-3">
                  <span
                    className={`w-7 h-7 rounded-full flex items-center justify-center text-xs font-bold ${
                      st.state === "DONE"
                        ? "bg-emerald-800 text-cream"
                        : st.state === "FAILED"
                        ? "bg-red-800 text-cream"
                        : st.state === "ACTIVE"
                        ? "bg-rust text-cream"
                        : st.state === "RUNNING"
                        ? "bg-purple-800 text-cream animate-pulse"
                        : "bg-blush text-clay"
                    }`}
                  >
                    {st.icon}
                  </span>
                  <div>
                    <div className="text-sm font-medium text-ink flex items-center gap-2">
                      {s.label}
                      {s.model && (
                        <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-softPaper border border-blush text-clay">
                          {s.model}
                        </span>
                      )}
                    </div>
                    {st.run?.error && (
                      <div className="text-xs text-red-700 mt-0.5">{st.run.error}</div>
                    )}
                  </div>
                </div>

                <div className="flex items-center gap-3 text-xs">
                  <span
                    className={`font-mono px-2 py-0.5 rounded ${
                      st.state === "DONE"
                        ? "text-emerald-800 font-semibold"
                        : st.state === "ACTIVE"
                        ? "text-rust font-semibold"
                        : "text-clay"
                    }`}
                  >
                    {st.text}
                  </span>

                  {s.reviewRoute && (
                    <Link
                      href={`/papers/${paperId}/${s.reviewRoute}`}
                      className="text-xs text-rust hover:underline font-medium"
                    >
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
