"use client";

import React, { useState, useEffect, useRef } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { apiRequest, API_BASE, showToast } from "@/lib/api";
import { useReviewer } from "@/components/ReviewerContext";
import {
  ArrowLeft,
  CheckCircle,
  AlertTriangle,
  AlertOctagon,
  Copy,
  RotateCw,
  ExternalLink,
  ChevronLeft,
  ChevronRight,
  Play,
  FileCheck,
  Eye,
  X,
  FileText
} from "lucide-react";

interface Violation {
  violationId: string;
  code: string;
  severity: "BLOCKING" | "WARNING";
  message: string;
  location?: string;
  overrideReason?: string;
}

interface Asset {
  assetId: string;
  paperId: string;
  type: "CAROUSEL" | "STATIC" | "REEL" | "CAPTION";
  status: "RENDERED" | "BLOCKED" | "STALE" | "APPROVED";
  content: any;
  renderUrls: string[];
  claimIds: string[];
  violations: Violation[];
  approvedBy?: string;
  approvedAt?: string;
}

interface Claim {
  id: string;
  passageId: string;
  scientificClaim: string;
  consumerDraft: string;
  editedCopy?: string;
  quote: string;
  quoteScore: number;
  numbersOk: boolean;
  findingClass: "PRIMARY" | "SECONDARY" | "EXPLORATORY";
  risk: "GREEN" | "AMBER" | "RED";
  doctorStatus: "PENDING" | "APPROVED" | "EDITED" | "REJECTED" | "NEEDS_EVIDENCE";
}

interface Passage {
  id: string;
  page: number;
  section: string;
  bbox?: string | { l: number; t: number; r: number; b: number };
  pageWidth?: number;
  pageHeight?: number;
  text: string;
}

export default function AssetReviewPage() {
  const params = useParams();
  const router = useRouter();
  const id = params.id as string;
  const { reviewer } = useReviewer();

  const [paper, setPaper] = useState<any>(null);
  const [assets, setAssets] = useState<Asset[]>([]);
  const [claims, setClaims] = useState<Record<string, Claim>>({});
  const [passages, setPassages] = useState<Record<string, Passage>>({});
  const [activeTab, setActiveTab] = useState<"CAROUSEL" | "STATIC" | "REEL" | "CAPTION">("CAROUSEL");
  const [loading, setLoading] = useState(true);
  const [approving, setApproving] = useState(false);
  const [regenerating, setRegenerating] = useState(false);

  // Carousel slide index (1-based)
  const [slideIdx, setSlideIdx] = useState(1);

  // Static image aspect ratio index
  const [staticRatioIdx, setStaticRatioIdx] = useState(0);

  // Selected block for right drawer
  const [selectedBlock, setSelectedBlock] = useState<{
    blockId: string;
    kind: string;
    claimIds: string[];
    text?: string;
  } | null>(null);

  // Reel video time
  const [videoTime, setVideoTime] = useState(0);
  const videoRef = useRef<HTMLVideoElement>(null);

  // Confirmation modal for warning override
  const [showOverrideModal, setShowOverrideModal] = useState(false);

  useEffect(() => {
    fetchData();
  }, [id]);

  async function fetchData() {
    try {
      setLoading(true);
      const [paperData, assetsData, claimsData, passagesData] = await Promise.all([
        apiRequest(`/api/papers/${id}`),
        apiRequest(`/api/papers/${id}/assets`),
        apiRequest(`/api/papers/${id}/claims`).catch(() => []),
        apiRequest(`/api/papers/${id}/passages`).catch(() => [])
      ]);

      setPaper(paperData);
      setAssets(assetsData);

      const claimsMap: Record<string, Claim> = {};
      claimsData.forEach((c: Claim) => {
        claimsMap[c.id] = c;
      });
      setClaims(claimsMap);

      const passagesMap: Record<string, Passage> = {};
      passagesData.forEach((p: Passage) => {
        passagesMap[p.id] = p;
      });
      setPassages(passagesMap);
    } catch (err: any) {
      console.error("Failed to load assets data", err);
    } finally {
      setLoading(false);
    }
  }

  const currentAsset = assets.find((a) => a.type === activeTab) || null;

  async function handleApprove(override = false) {
    if (!currentAsset) return;

    const blocking = currentAsset.violations.filter((v) => v.severity === "BLOCKING");
    if (blocking.length > 0) {
      showToast("Cannot approve asset with blocking violations", "error");
      return;
    }

    const warnings = currentAsset.violations.filter((v) => v.severity === "WARNING");
    if (warnings.length > 0 && !override) {
      setShowOverrideModal(true);
      return;
    }

    try {
      setApproving(true);
      await apiRequest(`/api/assets/${currentAsset.assetId}/approve`, {
        method: "POST",
        body: JSON.stringify({
          approvedBy: reviewer,
          overrideWarnings: override
        })
      });
      showToast(`Asset ${currentAsset.type} approved successfully`, "success");
      setShowOverrideModal(false);
      await fetchData();
    } catch (err: any) {
      // toast shown by apiRequest
    } finally {
      setApproving(false);
    }
  }

  async function handleRegenerate() {
    try {
      setRegenerating(true);
      await apiRequest(`/api/papers/${id}/generate`, { method: "POST" });
      showToast("Regeneration pipeline started", "info");
      router.push(`/papers/${id}`);
    } catch (err: any) {
      // toast shown by apiRequest
    } finally {
      setRegenerating(false);
    }
  }

  function handleCopyCaption() {
    if (!currentAsset || !currentAsset.content) return;
    const paras = currentAsset.content.paragraphs || [];
    const text = paras.map((p: any) => p.text).join("\n\n");
    const hashtags = (currentAsset.content.hashtags || []).join(" ");
    const fullText = `${text}\n\n${hashtags}`;
    navigator.clipboard.writeText(fullText);
    showToast("Caption and hashtags copied to clipboard!", "success");
  }

  if (loading) {
    return (
      <div className="max-w-6xl mx-auto py-12 px-6 text-center text-ink/70">
        <RotateCw className="w-8 h-8 animate-spin mx-auto text-rust mb-4" />
        <p className="font-serif text-xl">Loading Gate 2 review console...</p>
      </div>
    );
  }

  if (!currentAsset) {
    return (
      <div className="max-w-6xl mx-auto py-12 px-6 text-center text-ink">
        <p className="text-xl font-serif">No assets generated yet for this paper.</p>
        <Link
          href={`/papers/${id}`}
          className="inline-block mt-4 px-4 py-2 bg-rust text-white rounded hover:bg-rust/90 text-sm font-medium"
        >
          Return to Pipeline
        </Link>
      </div>
    );
  }

  const blockingViolations = currentAsset.violations.filter((v) => v.severity === "BLOCKING");
  const warningViolations = currentAsset.violations.filter((v) => v.severity === "WARNING");
  const isApproved = currentAsset.status === "APPROVED";
  const canApprove = !isApproved && blockingViolations.length === 0;

  // Blocks & slides
  const blocks = currentAsset.content?.blocks || [];
  const blockMap = currentAsset.content?.blockMap || {};
  const slides = currentAsset.content?.slides || [];
  const scenes = currentAsset.content?.scenes || [];
  const paragraphs = currentAsset.content?.paragraphs || [];
  const hashtags = currentAsset.content?.hashtags || [];

  return (
    <div className="max-w-7xl mx-auto space-y-6">
      {/* Top Header Bar */}
      <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4 border-b border-blush pb-4">
        <div className="flex items-center gap-3">
          <Link
            href={`/papers/${id}`}
            className="inline-flex items-center gap-1.5 text-xs text-ink/70 hover:text-ink font-medium"
          >
            <ArrowLeft className="w-3.5 h-3.5" /> Paper Pipeline
          </Link>
          <span className="text-blush">/</span>
          <h1 className="font-serif text-2xl font-bold text-ink">
            Gate 2: Asset Review
          </h1>
          <span className="text-xs px-2 py-0.5 rounded bg-cream border border-blush font-mono text-ink/70">
            {id}
          </span>
        </div>

        <div className="flex items-center gap-3">
          <Link
            href={`/papers/${id}/claims`}
            className="px-3 py-1.5 rounded border border-blush bg-softPaper hover:bg-cream text-xs font-medium text-ink flex items-center gap-1.5"
          >
            <FileText className="w-3.5 h-3.5 text-rust" /> Claims Console
          </Link>
          <Link
            href={`/papers/${id}/export`}
            className="px-3 py-1.5 rounded bg-maroon text-white hover:bg-maroon/90 text-xs font-medium flex items-center gap-1.5"
          >
            <FileCheck className="w-3.5 h-3.5" /> Export Gate
          </Link>
        </div>
      </div>

      {/* Asset Tabs & Actions */}
      <div className="bg-softPaper border border-blush rounded-lg p-4 flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
        {/* Tabs */}
        <div className="flex items-center gap-2">
          {(["CAROUSEL", "STATIC", "REEL", "CAPTION"] as const).map((tab) => {
            const assetObj = assets.find((a) => a.type === tab);
            const status = assetObj?.status || "RENDERED";
            const blockCount = assetObj?.violations.filter((v) => v.severity === "BLOCKING").length || 0;

            return (
              <button
                key={tab}
                onClick={() => {
                  setActiveTab(tab);
                  setSelectedBlock(null);
                }}
                className={`px-4 py-2 rounded text-sm font-medium transition flex items-center gap-2 ${
                  activeTab === tab
                    ? "bg-rust text-white shadow-sm"
                    : "bg-cream text-ink/80 hover:bg-blush/40"
                }`}
              >
                <span>{tab.charAt(0) + tab.slice(1).toLowerCase()}</span>
                {status === "APPROVED" && (
                  <CheckCircle className={`w-3.5 h-3.5 ${activeTab === tab ? "text-white" : "text-emerald-700"}`} />
                )}
                {blockCount > 0 && (
                  <span className="w-2 h-2 rounded-full bg-red-600" title="Has blocking violations" />
                )}
              </button>
            );
          })}
        </div>

        {/* Current Asset Status & Actions */}
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2 text-xs">
            <span className="text-ink/60">Status:</span>
            <span
              className={`px-2 py-0.5 rounded font-mono font-bold uppercase text-[11px] ${
                currentAsset.status === "APPROVED"
                  ? "bg-emerald-100 text-emerald-800 border border-emerald-300"
                  : currentAsset.status === "BLOCKED"
                  ? "bg-red-100 text-red-800 border border-red-300"
                  : currentAsset.status === "STALE"
                  ? "bg-amber-100 text-amber-800 border border-amber-300"
                  : "bg-blue-100 text-blue-800 border border-blue-300"
              }`}
            >
              {currentAsset.status}
            </span>
            {currentAsset.approvedBy && (
              <span className="text-ink/60 text-[11px] ml-1">
                by {currentAsset.approvedBy}
              </span>
            )}
          </div>

          <button
            onClick={handleRegenerate}
            disabled={regenerating}
            className="px-3 py-1.5 rounded border border-blush bg-cream text-xs font-medium text-ink hover:bg-softPaper flex items-center gap-1.5 disabled:opacity-50"
            title="Re-run Writer + Policy + Render pipeline"
          >
            <RotateCw className={`w-3.5 h-3.5 ${regenerating ? "animate-spin" : ""}`} />
            Regenerate
          </button>

          <button
            onClick={() => handleApprove(false)}
            disabled={!canApprove || approving}
            className={`px-4 py-1.5 rounded text-xs font-semibold flex items-center gap-1.5 shadow-sm transition ${
              isApproved
                ? "bg-emerald-700 text-white cursor-default"
                : canApprove
                ? "bg-rust text-white hover:bg-rust/90 cursor-pointer"
                : "bg-clay/40 text-ink/40 cursor-not-allowed"
            }`}
          >
            <CheckCircle className="w-3.5 h-3.5" />
            {isApproved ? "Approved ✓" : approving ? "Approving..." : "Approve Asset"}
          </button>
        </div>
      </div>

      {/* Main Review Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        {/* Left Column: Asset Preview (7 cols) */}
        <div className="lg:col-span-7 bg-softPaper border border-blush rounded-lg p-5 shadow-sm space-y-4">
          {/* CAROUSEL VIEW */}
          {activeTab === "CAROUSEL" && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-medium text-ink/70">
                    Slide {slideIdx} of {currentAsset.renderUrls.length || 7}
                  </span>
                  {slides[slideIdx - 1]?.kind && (
                    <span className="text-[10px] uppercase font-mono px-2 py-0.5 rounded bg-cream border border-blush text-ink/80">
                      {slides[slideIdx - 1]?.kind}
                    </span>
                  )}
                </div>

                <div className="flex items-center gap-2">
                  <button
                    onClick={() => setSlideIdx((prev) => Math.max(1, prev - 1))}
                    disabled={slideIdx <= 1}
                    className="p-1 rounded bg-cream border border-blush text-ink disabled:opacity-30 hover:bg-softPaper"
                  >
                    <ChevronLeft className="w-4 h-4" />
                  </button>
                  <span className="text-xs font-mono font-medium">{slideIdx}</span>
                  <button
                    onClick={() =>
                      setSlideIdx((prev) =>
                        Math.min(currentAsset.renderUrls.length || 7, prev + 1)
                      )
                    }
                    disabled={slideIdx >= (currentAsset.renderUrls.length || 7)}
                    className="p-1 rounded bg-cream border border-blush text-ink disabled:opacity-30 hover:bg-softPaper"
                  >
                    <ChevronRight className="w-4 h-4" />
                  </button>
                </div>
              </div>

              {/* Slide Container with Overlay Blocks */}
              <div className="relative mx-auto max-w-[440px] aspect-[4/5] bg-cream rounded-md overflow-hidden border border-blush shadow-inner">
                {currentAsset.renderUrls[slideIdx - 1] ? (
                  <img
                    src={`${API_BASE}/files/${currentAsset.renderUrls[slideIdx - 1]}`}
                    alt={`Slide ${slideIdx}`}
                    className="w-full h-full object-contain pointer-events-none select-none"
                  />
                ) : (
                  <div className="w-full h-full flex items-center justify-center text-ink/40 text-sm">
                    Rendering in progress...
                  </div>
                )}

                {/* Interactive Click-to-Source Overlays */}
                {blocks
                  .filter((b: any) => b.slide === slideIdx || b.image === `carousel/${slideIdx}.png`)
                  .map((block: any, idx: number) => {
                    const blockInfo = blockMap[block.blockId] || {};
                    const isSelected = selectedBlock?.blockId === block.blockId;

                    return (
                      <button
                        key={idx}
                        onClick={() =>
                          setSelectedBlock({
                            blockId: block.blockId,
                            kind: blockInfo.kind || "brand",
                            claimIds: blockInfo.claimIds || []
                          })
                        }
                        style={{
                          position: "absolute",
                          left: `${(block.x / 1080) * 100}%`,
                          top: `${(block.y / 1350) * 100}%`,
                          width: `${(block.w / 1080) * 100}%`,
                          height: `${(block.h / 1350) * 100}%`
                        }}
                        className={`group border transition-all rounded-sm cursor-pointer ${
                          isSelected
                            ? "border-rust bg-rust/20 ring-2 ring-rust"
                            : "border-transparent hover:border-rust hover:bg-rust/10"
                        }`}
                        title={`Click to view source (${blockInfo.kind || "block"})`}
                      >
                        <span className="opacity-0 group-hover:opacity-100 absolute -top-5 left-1 bg-ink text-white text-[9px] px-1 py-0.5 rounded font-mono pointer-events-none whitespace-nowrap shadow">
                          {blockInfo.kind || "source"}
                        </span>
                      </button>
                    );
                  })}
              </div>

              {/* Dots navigation */}
              <div className="flex justify-center items-center gap-1.5 pt-2">
                {Array.from({ length: currentAsset.renderUrls.length || 7 }).map((_, i) => (
                  <button
                    key={i}
                    onClick={() => setSlideIdx(i + 1)}
                    className={`h-2 rounded-full transition-all ${
                      slideIdx === i + 1 ? "w-6 bg-rust" : "w-2 bg-clay/50 hover:bg-clay"
                    }`}
                  />
                ))}
              </div>
            </div>
          )}

          {/* STATIC VIEW */}
          {activeTab === "STATIC" && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium text-ink/70">
                  Static Format:{" "}
                  {staticRatioIdx === 0
                    ? "Portrait (4:5, 1080x1350)"
                    : staticRatioIdx === 1
                    ? "Square (1:1, 1080x1080)"
                    : "Story (9:16, 1080x1920)"}
                </span>

                <div className="flex items-center gap-1">
                  {currentAsset.renderUrls.map((url, idx) => (
                    <button
                      key={idx}
                      onClick={() => setStaticRatioIdx(idx)}
                      className={`px-2.5 py-1 text-xs rounded border transition ${
                        staticRatioIdx === idx
                          ? "bg-rust text-white border-rust"
                          : "bg-cream border-blush text-ink/80 hover:bg-softPaper"
                      }`}
                    >
                      {idx === 0 ? "4:5" : idx === 1 ? "1:1" : "9:16"}
                    </button>
                  ))}
                </div>
              </div>

              {/* Static Container with Overlay Blocks */}
              <div
                className={`relative mx-auto max-w-[440px] bg-cream rounded-md overflow-hidden border border-blush shadow-inner ${
                  staticRatioIdx === 1 ? "aspect-square" : staticRatioIdx === 2 ? "aspect-[9/16]" : "aspect-[4/5]"
                }`}
              >
                {currentAsset.renderUrls[staticRatioIdx] ? (
                  <img
                    src={`${API_BASE}/files/${currentAsset.renderUrls[staticRatioIdx]}`}
                    alt="Static post"
                    className="w-full h-full object-contain pointer-events-none select-none"
                  />
                ) : (
                  <div className="w-full h-full flex items-center justify-center text-ink/40 text-sm">
                    No render available
                  </div>
                )}

                {/* Click-to-source overlays for static (shown primarily on portrait) */}
                {staticRatioIdx === 0 &&
                  blocks.map((block: any, idx: number) => {
                    const blockInfo = blockMap[block.blockId] || {};
                    const isSelected = selectedBlock?.blockId === block.blockId;

                    return (
                      <button
                        key={idx}
                        onClick={() =>
                          setSelectedBlock({
                            blockId: block.blockId,
                            kind: blockInfo.kind || "brand",
                            claimIds: blockInfo.claimIds || []
                          })
                        }
                        style={{
                          position: "absolute",
                          left: `${(block.x / 1080) * 100}%`,
                          top: `${(block.y / 1350) * 100}%`,
                          width: `${(block.w / 1080) * 100}%`,
                          height: `${(block.h / 1350) * 100}%`
                        }}
                        className={`group border transition-all rounded-sm cursor-pointer ${
                          isSelected
                            ? "border-rust bg-rust/20 ring-2 ring-rust"
                            : "border-transparent hover:border-rust hover:bg-rust/10"
                        }`}
                        title={`Click to view source (${blockInfo.kind || "block"})`}
                      >
                        <span className="opacity-0 group-hover:opacity-100 absolute -top-5 left-1 bg-ink text-white text-[9px] px-1 py-0.5 rounded font-mono pointer-events-none whitespace-nowrap shadow">
                          {blockInfo.kind || "source"}
                        </span>
                      </button>
                    );
                  })}
              </div>
            </div>
          )}

          {/* REEL VIEW */}
          {activeTab === "REEL" && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium text-ink/70">
                  Vertical Video (9:16) with Edge-TTS voiceover
                </span>
                <span className="text-xs font-mono text-ink/60">
                  {videoTime.toFixed(1)}s
                </span>
              </div>

              {/* Video Player */}
              <div className="relative mx-auto max-w-[320px] aspect-[9/16] bg-black rounded-lg overflow-hidden border border-blush shadow-lg">
                {currentAsset.renderUrls[0] ? (
                  <video
                    ref={videoRef}
                    src={`${API_BASE}/files/${currentAsset.renderUrls[0]}`}
                    controls
                    playsInline
                    onTimeUpdate={(e) => setVideoTime((e.target as HTMLVideoElement).currentTime)}
                    className="w-full h-full object-cover"
                  />
                ) : (
                  <div className="w-full h-full flex items-center justify-center text-white/50 text-sm">
                    Video rendering in progress...
                  </div>
                )}
              </div>

              {/* Scene list synced to currentTime */}
              <div className="space-y-2 pt-2 border-t border-blush">
                <h3 className="text-xs font-bold uppercase tracking-wider text-ink/60">
                  Storyline Scenes ({scenes.length})
                </h3>
                <div className="space-y-2 max-h-56 overflow-y-auto pr-1">
                  {scenes.map((scene: any, idx: number) => {
                    const isSelected = selectedBlock?.blockId === `scene-${scene.n}`;
                    return (
                      <div
                        key={idx}
                        onClick={() =>
                          setSelectedBlock({
                            blockId: `scene-${scene.n}`,
                            kind: scene.kind || "brand",
                            claimIds: scene.claimIds || []
                          })
                        }
                        className={`p-2.5 rounded border text-xs transition cursor-pointer flex flex-col gap-1 ${
                          isSelected
                            ? "bg-rust/10 border-rust"
                            : "bg-cream border-blush hover:bg-softPaper"
                        }`}
                      >
                        <div className="flex items-center justify-between">
                          <span className="font-bold text-ink flex items-center gap-1.5">
                            <span className="w-4 h-4 rounded-full bg-rust text-white text-[10px] inline-flex items-center justify-center">
                              {scene.n}
                            </span>
                            {scene.onScreen}
                          </span>
                          <span className="text-[10px] uppercase font-mono px-1.5 py-0.5 rounded bg-softPaper text-ink/70 border border-blush">
                            {scene.kind}
                          </span>
                        </div>
                        <p className="text-ink/80 italic text-[11px] leading-relaxed">
                          "{scene.voiceover}"
                        </p>
                        {scene.brollQuery && (
                          <div className="text-[10px] text-ink/50">
                            B-roll query: <span className="font-mono">{scene.brollQuery}</span>
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              </div>
            </div>
          )}

          {/* CAPTION VIEW */}
          {activeTab === "CAPTION" && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium text-ink/70">
                  Instagram & LinkedIn Post Caption
                </span>
                <button
                  onClick={handleCopyCaption}
                  className="px-3 py-1.5 rounded bg-rust text-white text-xs font-medium hover:bg-rust/90 flex items-center gap-1.5 shadow-sm"
                >
                  <Copy className="w-3.5 h-3.5" /> Copy Full Caption
                </button>
              </div>

              <div className="bg-cream border border-blush rounded-lg p-5 space-y-4 text-sm leading-relaxed text-ink shadow-inner font-sans">
                {paragraphs.map((p: any, idx: number) => (
                  <div key={idx} className="space-y-1">
                    <p>{p.text}</p>
                    {p.claimIds && p.claimIds.length > 0 && (
                      <div className="flex items-center gap-1.5 pt-1">
                        <span className="text-[10px] text-ink/50 uppercase font-bold">
                          Linked evidence:
                        </span>
                        {p.claimIds.map((cid: string) => (
                          <button
                            key={cid}
                            onClick={() =>
                              setSelectedBlock({
                                blockId: `caption-${idx}`,
                                kind: "claim",
                                claimIds: [cid]
                              })
                            }
                            className="text-[10px] font-mono px-2 py-0.5 rounded bg-softPaper border border-rust/40 text-rust hover:bg-rust hover:text-white transition"
                          >
                            {cid}
                          </button>
                        ))}
                      </div>
                    )}
                  </div>
                ))}

                {hashtags.length > 0 && (
                  <div className="pt-3 border-t border-blush/60 flex flex-wrap gap-1.5">
                    {hashtags.map((ht: string, idx: number) => (
                      <span
                        key={idx}
                        className="text-xs font-mono text-rust bg-softPaper px-2 py-0.5 rounded border border-blush"
                      >
                        {ht}
                      </span>
                    ))}
                  </div>
                )}
              </div>
            </div>
          )}
        </div>

        {/* Right Column: Reasoning, Violations, Click-to-Source Drawer (5 cols) */}
        <div className="lg:col-span-5 space-y-4">
          {/* Writer Reasoning Box */}
          <div className="bg-softPaper border border-blush rounded-lg p-4 shadow-sm space-y-3">
            <h3 className="text-xs font-bold uppercase tracking-wider text-ink/70 flex items-center gap-1.5">
              <Eye className="w-3.5 h-3.5 text-rust" /> Strategic Intent & Reasoning
            </h3>
            {currentAsset.content?.coreMessage && (
              <div>
                <span className="text-[11px] font-semibold text-ink/60 uppercase">Core Message:</span>
                <p className="text-xs font-medium text-ink mt-0.5 leading-snug">
                  "{currentAsset.content.coreMessage}"
                </p>
              </div>
            )}
            {currentAsset.content?.reasoning && (
              <div>
                <span className="text-[11px] font-semibold text-ink/60 uppercase">Clinical Framing:</span>
                <p className="text-xs text-ink/80 mt-0.5 leading-relaxed bg-cream p-2.5 rounded border border-blush/60">
                  {currentAsset.content.reasoning}
                </p>
              </div>
            )}
          </div>

          {/* Violations Panel */}
          <div className="bg-softPaper border border-blush rounded-lg p-4 shadow-sm space-y-3">
            <div className="flex items-center justify-between">
              <h3 className="text-xs font-bold uppercase tracking-wider text-ink/70 flex items-center gap-1.5">
                <FileCheck className="w-3.5 h-3.5 text-rust" /> Policy Compliance Check
              </h3>
              <span className="text-xs font-mono">
                {currentAsset.violations.length} issue(s)
              </span>
            </div>

            {currentAsset.violations.length === 0 ? (
              <div className="bg-emerald-50 border border-emerald-200 rounded p-3 text-xs text-emerald-800 flex items-center gap-2">
                <CheckCircle className="w-4 h-4 text-emerald-600 flex-shrink-0" />
                <span>0 policy violations. Safe for clinical review and approval.</span>
              </div>
            ) : (
              <div className="space-y-2">
                {currentAsset.violations.map((v, idx) => (
                  <div
                    key={idx}
                    className={`p-2.5 rounded border text-xs space-y-1 ${
                      v.severity === "BLOCKING"
                        ? "bg-red-50 border-red-200 text-red-900"
                        : "bg-amber-50 border-amber-200 text-amber-900"
                    }`}
                  >
                    <div className="flex items-center justify-between">
                      <span className="font-bold flex items-center gap-1">
                        {v.severity === "BLOCKING" ? (
                          <AlertOctagon className="w-3.5 h-3.5 text-red-600" />
                        ) : (
                          <AlertTriangle className="w-3.5 h-3.5 text-amber-600" />
                        )}
                        {v.code}
                      </span>
                      <span
                        className={`text-[10px] font-mono px-1.5 py-0.2 rounded font-bold uppercase ${
                          v.severity === "BLOCKING"
                            ? "bg-red-200 text-red-800"
                            : "bg-amber-200 text-amber-800"
                        }`}
                      >
                        {v.severity}
                      </span>
                    </div>
                    <p className="text-[11px] leading-tight">{v.message}</p>
                    {v.location && (
                      <div className="text-[10px] text-ink/60 font-mono">
                        location: {v.location}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Click-to-Source Drawer */}
          <div className="bg-softPaper border border-blush rounded-lg p-4 shadow-sm space-y-3">
            <div className="flex items-center justify-between border-b border-blush pb-2">
              <h3 className="text-xs font-bold uppercase tracking-wider text-ink/70 flex items-center gap-1.5">
                <FileText className="w-3.5 h-3.5 text-rust" /> Source Evidence Inspector
              </h3>
              {selectedBlock && (
                <button
                  onClick={() => setSelectedBlock(null)}
                  className="text-ink/50 hover:text-ink"
                >
                  <X className="w-4 h-4" />
                </button>
              )}
            </div>

            {!selectedBlock ? (
              <div className="text-center py-6 text-xs text-ink/50 space-y-1">
                <p>Click any text block, reel scene, or claim chip</p>
                <p className="text-[11px] text-ink/40">to inspect verified clinical evidence</p>
              </div>
            ) : (
              <div className="space-y-4">
                <div className="flex items-center justify-between">
                  <span className="text-xs font-mono font-medium text-ink/80">
                    {selectedBlock.blockId}
                  </span>
                  <span
                    className={`text-[10px] font-mono font-bold uppercase px-2 py-0.5 rounded ${
                      selectedBlock.kind === "claim"
                        ? "bg-rust/20 text-rust border border-rust/40"
                        : selectedBlock.kind === "illustration"
                        ? "bg-clay/20 text-clay border border-clay/40"
                        : "bg-maroon/20 text-maroon border border-maroon/40"
                    }`}
                  >
                    {selectedBlock.kind}
                  </span>
                </div>

                {selectedBlock.kind === "brand" && (
                  <div className="bg-cream border border-blush rounded p-3 text-xs text-ink/80">
                    <p className="font-semibold text-ink">Brand line</p>
                    <p className="text-[11px] text-ink/60 mt-1">
                      Non-claim copy setting context, branding, or clinic consultation call-to-action.
                    </p>
                  </div>
                )}

                {selectedBlock.kind === "illustration" && (
                  <div className="bg-cream border border-blush rounded p-3 text-xs text-ink/80">
                    <p className="font-semibold text-ink">Contextual Illustration</p>
                    <p className="text-[11px] text-ink/60 mt-1">
                      Illustration, not a study finding. Designed to relate medical concepts to everyday patient routines.
                    </p>
                  </div>
                )}

                {selectedBlock.kind === "claim" && (
                  <div className="space-y-3">
                    {selectedBlock.claimIds.length === 0 ? (
                      <p className="text-xs text-ink/60 italic">No claim IDs attached to this block.</p>
                    ) : (
                      selectedBlock.claimIds.map((cid) => {
                        const claim = claims[cid];
                        if (!claim) {
                          return (
                            <div key={cid} className="text-xs text-ink/60">
                              Claim ID: <span className="font-mono">{cid}</span> (not found)
                            </div>
                          );
                        }

                        const passage = passages[claim.passageId];

                        return (
                          <div
                            key={cid}
                            className="bg-cream border border-blush rounded-md p-3 space-y-2 text-xs shadow-sm"
                          >
                            <div className="flex items-center justify-between">
                              <span className="font-mono font-bold text-rust text-[11px]">
                                {claim.id}
                              </span>
                              <div className="flex items-center gap-1.5">
                                <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-softPaper border border-blush">
                                  {claim.findingClass}
                                </span>
                                <span
                                  className={`text-[10px] font-bold px-1.5 py-0.5 rounded uppercase ${
                                    claim.risk === "GREEN"
                                      ? "bg-emerald-100 text-emerald-800"
                                      : claim.risk === "AMBER"
                                      ? "bg-amber-100 text-amber-800"
                                      : "bg-red-100 text-red-800"
                                  }`}
                                >
                                  {claim.risk}
                                </span>
                              </div>
                            </div>

                            <div>
                              <div className="text-[10px] font-bold text-ink/60 uppercase">
                                Scientific Finding:
                              </div>
                              <p className="text-ink font-medium leading-tight mt-0.5">
                                {claim.scientificClaim}
                              </p>
                            </div>

                            <div>
                              <div className="text-[10px] font-bold text-ink/60 uppercase">
                                Direct Quote from Paper:
                              </div>
                              <p className="text-ink/80 italic bg-softPaper p-2 rounded border border-blush/60 mt-0.5 leading-snug">
                                "{claim.quote}"
                              </p>
                              <div className="flex items-center justify-between text-[10px] text-ink/50 mt-1">
                                <span>Quote match: {claim.quoteScore}%</span>
                                <span>Numbers: {claim.numbersOk ? "✓ verified" : "✗ mismatch"}</span>
                              </div>
                            </div>

                            {passage && (
                              <div className="pt-2 border-t border-blush/60 space-y-1">
                                <div className="flex items-center justify-between text-[11px]">
                                  <span className="font-bold text-ink">
                                    Page {passage.page} · {passage.section}
                                  </span>
                                  <Link
                                    href={`/papers/${id}/claims`}
                                    className="text-rust hover:underline inline-flex items-center gap-1 text-[10px]"
                                  >
                                    Gate 1 viewer <ExternalLink className="w-3 h-3" />
                                  </Link>
                                </div>
                                <div className="relative border border-blush rounded overflow-hidden max-h-36 bg-white">
                                  <img
                                    src={`${API_BASE}/files/pages/${id}/${passage.page}.png`}
                                    alt={`Page ${passage.page}`}
                                    className="w-full object-contain"
                                  />
                                </div>
                              </div>
                            )}
                          </div>
                        );
                      })
                    )}
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Confirmation Modal for Overriding Warnings */}
      {showOverrideModal && (
        <div className="fixed inset-0 bg-ink/50 flex items-center justify-center p-4 z-50">
          <div className="bg-cream border border-blush rounded-lg max-w-md w-full p-6 space-y-4 shadow-xl">
            <div className="flex items-center gap-3 text-amber-700">
              <AlertTriangle className="w-6 h-6 flex-shrink-0" />
              <h3 className="font-serif text-lg font-bold text-ink">
                Approve with Warnings?
              </h3>
            </div>
            <p className="text-xs text-ink/80 leading-relaxed">
              This asset contains {warningViolations.length} warning violation(s). You can approve it with an explicit clinician override, which will be logged in the immutable audit trail.
            </p>
            <div className="max-h-36 overflow-y-auto space-y-1.5 p-2 bg-softPaper rounded border border-blush text-xs">
              {warningViolations.map((w, idx) => (
                <div key={idx} className="text-ink/80">
                  <span className="font-mono font-bold text-amber-800">{w.code}:</span> {w.message}
                </div>
              ))}
            </div>
            <div className="flex justify-end gap-2 pt-2">
              <button
                onClick={() => setShowOverrideModal(false)}
                className="px-3 py-1.5 rounded border border-blush bg-softPaper text-xs font-medium text-ink hover:bg-cream"
              >
                Cancel
              </button>
              <button
                onClick={() => handleApprove(true)}
                disabled={approving}
                className="px-4 py-1.5 rounded bg-rust text-white text-xs font-medium hover:bg-rust/90 shadow-sm"
              >
                {approving ? "Approving..." : "Confirm Override & Approve"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
