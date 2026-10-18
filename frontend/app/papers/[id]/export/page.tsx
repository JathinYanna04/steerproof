"use client";

import React, { useState, useEffect } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { apiRequest, API_BASE, showToast } from "@/lib/api";
import { useReviewer } from "@/components/ReviewerContext";
import {
  ArrowLeft,
  Download,
  CheckCircle,
  AlertOctagon,
  FileText,
  FileCheck,
  RotateCw,
  ExternalLink,
  ShieldCheck,
  Clock,
  Layers,
  FileCode,
  Image as ImageIcon,
  Video
} from "lucide-react";

interface BlockedItem {
  assetId?: string;
  type?: string;
  status?: string;
  reason?: string;
}

interface ExportResult {
  paperId: string;
  status: string;
  zipUrl: string;
  stats?: {
    claimsTotal: number;
    claimsApproved: number;
    assetsApproved: number;
    totalHumanSeconds: number;
  };
}

export default function ExportPage() {
  const params = useParams();
  const id = params.id as string;
  const { reviewer } = useReviewer();

  const [paper, setPaper] = useState<any>(null);
  const [assets, setAssets] = useState<any[]>([]);
  const [claims, setClaims] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [exporting, setExporting] = useState(false);
  const [exportResult, setExportResult] = useState<ExportResult | null>(null);
  const [blockedBy, setBlockedBy] = useState<BlockedItem[]>([]);

  useEffect(() => {
    fetchData();
  }, [id]);

  async function fetchData() {
    try {
      setLoading(true);
      const [paperData, assetsData, claimsData] = await Promise.all([
        apiRequest(`/api/papers/${id}`),
        apiRequest(`/api/papers/${id}/assets`).catch(() => []),
        apiRequest(`/api/papers/${id}/claims`).catch(() => [])
      ]);

      setPaper(paperData);
      setAssets(assetsData);
      setClaims(claimsData);

      // Check if already exported
      if (paperData.status === "EXPORTED") {
        setExportResult({
          paperId: id,
          status: "EXPORTED",
          zipUrl: `/files/exports/${id}.zip`,
          stats: {
            claimsTotal: paperData.stats?.claimsTotal || claimsData.length,
            claimsApproved: paperData.stats?.claimsApproved || 0,
            assetsApproved: assetsData.filter((a: any) => a.status === "APPROVED").length,
            totalHumanSeconds: paperData.stats?.humanSecondsTotal || 0
          }
        });
      }
    } catch (err: any) {
      console.error("Failed to load export data", err);
    } finally {
      setLoading(false);
    }
  }

  async function handleExport() {
    try {
      setExporting(true);
      setBlockedBy([]);
      const res = await apiRequest<ExportResult>(`/api/papers/${id}/export`, {
        method: "POST"
      });
      setExportResult(res);
      showToast("Paper exported and signed successfully!", "success");
      await fetchData();
    } catch (err: any) {
      if (err.status === 422) {
        const blockers = err.blockedBy || err.details?.blockedBy || [];
        setBlockedBy(blockers);
      }
    } finally {
      setExporting(false);
    }
  }

  if (loading) {
    return (
      <div className="max-w-4xl mx-auto py-12 px-6 text-center text-ink/70">
        <RotateCw className="w-8 h-8 animate-spin mx-auto text-rust mb-4" />
        <p className="font-serif text-xl">Loading export console...</p>
      </div>
    );
  }

  // Pre-check blockers on client side as well
  const pendingClaims = claims.filter(
    (c) => c.doctorStatus === "PENDING" || c.doctorStatus === "NEEDS_EVIDENCE"
  );
  const unapprovedAssets = assets.filter((a) => a.status !== "APPROVED");
  const isReady = unapprovedAssets.length === 0 && assets.length > 0;

  return (
    <div className="max-w-4xl mx-auto space-y-6">
      {/* Top Header Bar */}
      <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4 border-b border-blush pb-4">
        <div className="flex items-center gap-3">
          <Link
            href={`/papers/${id}/assets`}
            className="inline-flex items-center gap-1.5 text-xs text-ink/70 hover:text-ink font-medium"
          >
            <ArrowLeft className="w-3.5 h-3.5" /> Gate 2 Assets
          </Link>
          <span className="text-blush">/</span>
          <h1 className="font-serif text-2xl font-bold text-ink">
            Export Content Package
          </h1>
          <span className="text-xs px-2 py-0.5 rounded bg-cream border border-blush font-mono text-ink/70">
            {id}
          </span>
        </div>

        <div className="flex items-center gap-2 text-xs">
          <span className="text-ink/60">Reviewer:</span>
          <span className="font-medium text-ink bg-softPaper border border-blush px-2 py-1 rounded">
            {reviewer}
          </span>
        </div>
      </div>

      {/* Main Export Action Card */}
      <div className="bg-softPaper border border-blush rounded-xl p-6 sm:p-8 shadow-sm space-y-6">
        <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-6">
          <div className="space-y-2">
            <h2 className="font-serif text-2xl font-bold text-ink flex items-center gap-2">
              <ShieldCheck className="w-6 h-6 text-rust" />
              Production Release Packaging
            </h2>
            <p className="text-xs sm:text-sm text-ink/70 max-w-xl leading-relaxed">
              SteerProof bundles only verified and clinician-approved creative assets.
              Every claim is cited to exact study passages, policy violations are cleared,
              and full audit provenance is compiled.
            </p>
          </div>

          {!exportResult && (
            <button
              onClick={handleExport}
              disabled={exporting}
              className={`px-6 py-3 rounded-lg text-sm font-semibold flex items-center gap-2 shadow-md transition whitespace-nowrap ${
                isReady
                  ? "bg-rust text-white hover:bg-rust/90 cursor-pointer"
                  : "bg-clay/40 text-ink/40 cursor-not-allowed hover:bg-clay/50"
              }`}
            >
              {exporting ? (
                <>
                  <RotateCw className="w-4 h-4 animate-spin" /> Packaging ZIP...
                </>
              ) : (
                <>
                  <Download className="w-4 h-4" /> Export Approved Content
                </>
              )}
            </button>
          )}
        </div>

        {/* Success State */}
        {exportResult && (
          <div className="bg-emerald-50 border border-emerald-300 rounded-lg p-6 space-y-4 shadow-inner">
            <div className="flex items-center justify-between flex-wrap gap-4">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-full bg-emerald-700 text-white flex items-center justify-center flex-shrink-0 shadow">
                  <CheckCircle className="w-6 h-6" />
                </div>
                <div>
                  <h3 className="font-serif text-lg font-bold text-emerald-950">
                    Package Exported & Digitally Signed
                  </h3>
                  <p className="text-xs text-emerald-800">
                    ZIP bundle generated with complete source citations and clinical audit report.
                  </p>
                </div>
              </div>

              <a
                href={`${API_BASE}${exportResult.zipUrl}`}
                download
                className="px-5 py-2.5 rounded-lg bg-emerald-700 text-white font-medium text-xs hover:bg-emerald-800 transition shadow flex items-center gap-2"
              >
                <Download className="w-4 h-4" /> Download ZIP Archive
              </a>
            </div>

            {/* Metrics Breakdown Card */}
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 pt-4 border-t border-emerald-200">
              <div className="bg-white/80 p-3 rounded border border-emerald-200">
                <span className="text-[10px] text-emerald-700 uppercase font-bold tracking-wider">
                  Claims Reviewed
                </span>
                <p className="font-serif text-xl font-bold text-emerald-950 mt-0.5">
                  {claims.filter((c) => c.doctorStatus === "APPROVED" || c.doctorStatus === "EDITED").length} / {claims.length}
                </p>
              </div>
              <div className="bg-white/80 p-3 rounded border border-emerald-200">
                <span className="text-[10px] text-emerald-700 uppercase font-bold tracking-wider">
                  Assets Approved
                </span>
                <p className="font-serif text-xl font-bold text-emerald-950 mt-0.5">
                  {assets.filter((a) => a.status === "APPROVED").length} / {assets.length}
                </p>
              </div>
              <div className="bg-white/80 p-3 rounded border border-emerald-200">
                <span className="text-[10px] text-emerald-700 uppercase font-bold tracking-wider">
                  Human Review Time
                </span>
                <p className="font-serif text-xl font-bold text-emerald-950 mt-0.5">
                  {Math.round((paper?.stats?.humanSecondsTotal || 0) / 60)} min
                </p>
              </div>
              <div className="bg-white/80 p-3 rounded border border-emerald-200">
                <span className="text-[10px] text-emerald-700 uppercase font-bold tracking-wider">
                  Pipeline Compute
                </span>
                <p className="font-serif text-xl font-bold text-emerald-950 mt-0.5">
                  {Math.round((paper?.stats?.pipelineSeconds || 0) / 60)} min
                </p>
              </div>
            </div>
          </div>
        )}

        {/* Blocked Checklist (if 422 returned or pre-check failed) */}
        {(!exportResult && (blockedBy.length > 0 || !isReady)) && (
          <div className="bg-amber-50 border border-amber-300 rounded-lg p-5 space-y-4">
            <div className="flex items-center gap-2.5 text-amber-900">
              <AlertOctagon className="w-5 h-5 text-amber-700 flex-shrink-0" />
              <h3 className="font-serif text-base font-bold">
                Export Blocked by Clinical Safety Gates
              </h3>
            </div>
            <p className="text-xs text-amber-950/80 leading-relaxed">
              SteerProof prevents publication of unverified or unapproved medical content.
              Resolve the following items before exporting:
            </p>

            <div className="space-y-2">
              {/* Unapproved Assets */}
              {unapprovedAssets.map((asset) => (
                <div
                  key={asset.assetId}
                  className="bg-white border border-amber-200 rounded p-3 text-xs flex flex-col sm:flex-row items-start sm:items-center justify-between gap-2 shadow-xs"
                >
                  <div className="space-y-0.5">
                    <div className="flex items-center gap-2">
                      <span className="font-bold text-ink font-serif text-sm">
                        {asset.type}
                      </span>
                      <span className="font-mono text-[10px] text-ink/60">
                        {asset.assetId}
                      </span>
                      <span className="font-mono text-[10px] uppercase font-bold px-1.5 py-0.5 rounded bg-amber-100 text-amber-800 border border-amber-300">
                        Status: {asset.status}
                      </span>
                    </div>
                    <p className="text-ink/70 text-[11px]">
                      Asset requires clinician review and approval in Gate 2.
                    </p>
                  </div>

                  <Link
                    href={`/papers/${id}/assets`}
                    className="px-3 py-1.5 rounded bg-rust text-white text-xs font-medium hover:bg-rust/90 flex items-center gap-1 shadow-xs"
                  >
                    Review Asset <ExternalLink className="w-3 h-3" />
                  </Link>
                </div>
              ))}

              {/* Pending Claims */}
              {pendingClaims.length > 0 && (
                <div className="bg-white border border-amber-200 rounded p-3 text-xs flex flex-col sm:flex-row items-start sm:items-center justify-between gap-2 shadow-xs">
                  <div className="space-y-0.5">
                    <div className="flex items-center gap-2">
                      <span className="font-bold text-ink font-serif text-sm">
                        Gate 1 Claims
                      </span>
                      <span className="font-mono text-[10px] uppercase font-bold px-1.5 py-0.5 rounded bg-red-100 text-red-800 border border-red-300">
                        {pendingClaims.length} Unreviewed
                      </span>
                    </div>
                    <p className="text-ink/70 text-[11px]">
                      All paper claims must be approved, edited, or rejected by a reviewer before content is written.
                    </p>
                  </div>

                  <Link
                    href={`/papers/${id}/claims`}
                    className="px-3 py-1.5 rounded bg-rust text-white text-xs font-medium hover:bg-rust/90 flex items-center gap-1 shadow-xs"
                  >
                    Review Claims <ExternalLink className="w-3 h-3" />
                  </Link>
                </div>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Package Contents Manifest Preview */}
      <div className="bg-softPaper border border-blush rounded-xl p-6 shadow-sm space-y-4">
        <h3 className="font-serif text-lg font-bold text-ink flex items-center gap-2">
          <Layers className="w-4 h-4 text-rust" /> Export Package Contents Manifest
        </h3>
        <p className="text-xs text-ink/70">
          The generated <span className="font-mono">{id}.zip</span> archive contains the following verified deliverables:
        </p>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-xs">
          <div className="bg-cream border border-blush rounded p-3 flex items-start gap-3">
            <ImageIcon className="w-5 h-5 text-rust flex-shrink-0 mt-0.5" />
            <div>
              <span className="font-mono font-bold text-ink">carousel/1..n.png</span>
              <p className="text-ink/70 text-[11px] mt-0.5">
                Full 4:5 vertical carousel slide PNGs rendered by Playwright with Instrument Serif typography and Steer brand palette.
              </p>
            </div>
          </div>

          <div className="bg-cream border border-blush rounded p-3 flex items-start gap-3">
            <ImageIcon className="w-5 h-5 text-rust flex-shrink-0 mt-0.5" />
            <div>
              <span className="font-mono font-bold text-ink">static.png (+ 1:1, 9:16)</span>
              <p className="text-ink/70 text-[11px] mt-0.5">
                Single-image stat cards rendered in portrait (4:5), square (1:1), and vertical story (9:16) aspect ratios.
              </p>
            </div>
          </div>

          <div className="bg-cream border border-blush rounded p-3 flex items-start gap-3">
            <Video className="w-5 h-5 text-rust flex-shrink-0 mt-0.5" />
            <div>
              <span className="font-mono font-bold text-ink">reel.mp4</span>
              <p className="text-ink/70 text-[11px] mt-0.5">
                25–40s Remotion MP4 video with authentic ambient audio track and neural Edge-TTS voiceover.
              </p>
            </div>
          </div>

          <div className="bg-cream border border-blush rounded p-3 flex items-start gap-3">
            <FileText className="w-5 h-5 text-rust flex-shrink-0 mt-0.5" />
            <div>
              <span className="font-mono font-bold text-ink">caption.txt</span>
              <p className="text-ink/70 text-[11px] mt-0.5">
                Clean Instagram & LinkedIn copy with verified claim paragraphs and curated hashtags.
              </p>
            </div>
          </div>

          <div className="bg-cream border border-blush rounded p-3 flex items-start gap-3">
            <FileCheck className="w-5 h-5 text-rust flex-shrink-0 mt-0.5" />
            <div>
              <span className="font-mono font-bold text-ink">sources.md</span>
              <p className="text-ink/70 text-[11px] mt-0.5">
                Full claim verification table referencing study page, section, direct quote, and academic citation.
              </p>
            </div>
          </div>

          <div className="bg-cream border border-blush rounded p-3 flex items-start gap-3">
            <FileCode className="w-5 h-5 text-rust flex-shrink-0 mt-0.5" />
            <div>
              <span className="font-mono font-bold text-ink">review_report.json</span>
              <p className="text-ink/70 text-[11px] mt-0.5">
                Machine-readable audit record detailing reviewer timestamps, doctor notes, and policy overrides.
              </p>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
