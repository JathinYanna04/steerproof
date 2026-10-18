"use client";

import React, { useState, useEffect, useRef, useMemo } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { apiRequest, fileUrl, showToast } from "@/lib/api";
import { useReviewer } from "@/components/ReviewerContext";
import { SourceDrawer, type ClaimRecord } from "@/components/SourceDrawer";
import {
  activeSceneAt,
  isContentBlock,
  normalizeViolation,
  plainText,
  resolveBlock,
  sceneSpans,
  type BlockKind,
  type Selection,
  type Violation,
} from "@/lib/assets";
import {
  ArrowLeft,
  CheckCircle,
  AlertTriangle,
  AlertOctagon,
  Copy,
  RotateCw,
  ChevronLeft,
  ChevronRight,
  FileCheck,
  Eye,
  FileText,
} from "lucide-react";

type AssetType = "CAROUSEL" | "STATIC" | "REEL" | "CAPTION";

interface Asset {
  assetId: string;
  paperId: string;
  type: AssetType;
  status: "DRAFT" | "RENDERED" | "BLOCKED" | "STALE" | "APPROVED";
  content: any;
  renderUrls: string[];
  claimIds: string[];
  violations: Violation[];
  approvedBy?: string;
  approvedAt?: string;
}

interface RenderedBlock {
  blockId: string;
  x: number;
  y: number;
  w: number;
  h: number;
  slide?: number | null;
  image?: string;
}

const TABS: AssetType[] = ["CAROUSEL", "STATIC", "REEL", "CAPTION"];

/** Why Approve is disabled, in the reviewer's words; null when the asset can be approved. */
function approveBlockReason(asset: Asset, claims: Record<string, ClaimRecord>): string | null {
  if (asset.status === "APPROVED") return null;
  if (asset.status === "STALE") return "A linked claim changed after rendering. Regenerate this asset first.";
  const blocking = asset.violations.filter((v) => v.severity === "BLOCKING" && !v.resolved);
  if (blocking.length > 0) {
    const rules = Array.from(new Set(blocking.map((v) => v.rule))).join(", ");
    return `${blocking.length} blocking policy violation(s): ${rules}`;
  }
  if (asset.status !== "RENDERED") return `Asset status is ${asset.status}; only RENDERED assets can be approved.`;
  const unapproved = asset.claimIds.filter((cid) => {
    const s = claims[cid]?.doctorStatus;
    return s !== "APPROVED" && s !== "EDITED";
  });
  if (unapproved.length > 0) return `Linked claims not approved at Gate 1: ${unapproved.join(", ")}`;
  return null;
}

function kindBadgeClass(kind: BlockKind | string) {
  return kind === "claim"
    ? "bg-rust/15 text-rust border-rust/40"
    : kind === "illustration"
    ? "bg-clay/20 text-clay border-clay/40"
    : "bg-maroon/15 text-maroon border-maroon/40";
}

export default function AssetReviewPage() {
  const params = useParams();
  const router = useRouter();
  const id = params.id as string;
  const { reviewerName } = useReviewer();

  const [assets, setAssets] = useState<Asset[]>([]);
  const [claims, setClaims] = useState<Record<string, ClaimRecord>>({});
  const [activeTab, setActiveTab] = useState<AssetType>("CAROUSEL");
  const [loading, setLoading] = useState(true);
  const [approving, setApproving] = useState(false);
  const [regenerating, setRegenerating] = useState(false);
  const [slideIdx, setSlideIdx] = useState(1);
  const [staticRatioIdx, setStaticRatioIdx] = useState(0);
  const [selection, setSelection] = useState<Selection | null>(null);
  const [showOverrideModal, setShowOverrideModal] = useState(false);

  // Reel playback
  const videoRef = useRef<HTMLVideoElement>(null);
  const [videoTime, setVideoTime] = useState(0);
  const [videoDuration, setVideoDuration] = useState<number | null>(null);
  const [audioDurationsMs, setAudioDurationsMs] = useState<(number | null)[] | null>(null);

  // Seconds the reviewer spends on the current asset tab, sent with the approval
  const tabOpenedAt = useRef<number>(Date.now());

  useEffect(() => {
    fetchData();
  }, [id]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    tabOpenedAt.current = Date.now();
  }, [activeTab]);

  async function fetchData() {
    try {
      setLoading(true);
      const [assetsData, claimsData] = await Promise.all([
        apiRequest<any[]>(`/api/papers/${id}/assets`),
        apiRequest<ClaimRecord[]>(`/api/papers/${id}/claims`).catch(() => [] as ClaimRecord[]),
      ]);
      setAssets(
        assetsData.map((a) => ({
          ...a,
          renderUrls: a.renderUrls || [],
          claimIds: a.claimIds || [],
          violations: (a.violations || []).map(normalizeViolation),
        }))
      );
      const map: Record<string, ClaimRecord> = {};
      claimsData.forEach((c) => {
        map[c.claimId] = c;
      });
      setClaims(map);
    } catch {
      // apiRequest already toasted the server error
    } finally {
      setLoading(false);
    }
  }

  const currentAsset = assets.find((a) => a.type === activeTab) || null;
  const reelAsset = assets.find((a) => a.type === "REEL") || null;
  const scenes: any[] = reelAsset?.content?.scenes || [];

  // Per-scene voiceover lengths drive the reel timeline when the render didn't store scene times
  useEffect(() => {
    setAudioDurationsMs(null);
    if (!reelAsset || scenes.length === 0) return;
    const hasTiming = scenes.every(
      (s) => (s.startSec ?? s.start ?? s.startMs) != null || typeof s.durationMs === "number"
    );
    if (hasTiming) return;
    let cancelled = false;
    Promise.all(
      scenes.map(
        (s, i) =>
          new Promise<number | null>((resolve) => {
            const audio = new Audio();
            audio.preload = "metadata";
            audio.onloadedmetadata = () => resolve(Number.isFinite(audio.duration) ? audio.duration * 1000 : null);
            audio.onerror = () => resolve(null);
            audio.src = fileUrl(`audio/${reelAsset.assetId}/scene-${s.n ?? i + 1}.mp3`);
          })
      )
    ).then((d) => {
      if (!cancelled) setAudioDurationsMs(d);
    });
    return () => {
      cancelled = true;
    };
  }, [reelAsset?.assetId, reelAsset?.content]); // eslint-disable-line react-hooks/exhaustive-deps

  const spans = useMemo(
    () => sceneSpans(scenes, audioDurationsMs, videoDuration),
    [scenes, audioDurationsMs, videoDuration]
  );
  const activeScene = activeSceneAt(spans, videoTime);

  function selectScene(scene: any, seek: boolean) {
    const kind: BlockKind = scene.kind === "claim" || scene.kind === "illustration" ? scene.kind : "brand";
    setSelection({
      blockId: `scene${scene.n}.onScreen`,
      kind,
      claimIds: kind === "claim" ? scene.claimIds || [] : [],
      text: scene.onScreen,
    });
    const span = spans?.find((s) => s.n === Number(scene.n));
    if (seek && span && videoRef.current) {
      // A hair past the boundary so the frame shown belongs to this scene
      videoRef.current.currentTime = span.start + 0.05;
      setVideoTime(span.start + 0.05);
    }
  }

  async function handleApprove(override = false) {
    if (!currentAsset) return;
    const reason = approveBlockReason(currentAsset, claims);
    if (reason) {
      showToast(reason, "error");
      return;
    }
    const warnings = currentAsset.violations.filter((v) => v.severity === "WARNING" && !v.resolved);
    if (warnings.length > 0 && !override) {
      setShowOverrideModal(true);
      return;
    }
    try {
      setApproving(true);
      await apiRequest(`/api/assets/${currentAsset.assetId}/approve`, {
        method: "POST",
        body: JSON.stringify({
          doctorName: reviewerName,
          reviewSeconds: Math.max(1, Math.round((Date.now() - tabOpenedAt.current) / 1000)),
          overrideWarnings: override,
        }),
      });
      showToast(`${currentAsset.type.toLowerCase()} approved`, "success");
      setShowOverrideModal(false);
      await fetchData();
    } catch {
      // apiRequest already toasted the server error verbatim
    } finally {
      setApproving(false);
    }
  }

  async function handleRegenerate() {
    if (!currentAsset) return;
    try {
      setRegenerating(true);
      await apiRequest(`/api/assets/${currentAsset.assetId}/regenerate`, { method: "POST" });
      showToast(`Regenerating ${currentAsset.type.toLowerCase()}`, "info");
      router.push(`/papers/${id}`);
    } catch {
      // apiRequest already toasted the server error verbatim
    } finally {
      setRegenerating(false);
    }
  }

  function handleCopyCaption() {
    if (!currentAsset?.content) return;
    const text = (currentAsset.content.paragraphs || []).map((p: any) => p.text).join("\n\n");
    const hashtags = (currentAsset.content.hashtags || []).join(" ");
    navigator.clipboard.writeText(`${text}\n\n${hashtags}`);
    showToast("Caption and hashtags copied", "success");
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
          className="inline-block mt-4 px-4 py-2 bg-rust text-cream rounded hover:bg-rust/90 text-sm font-medium"
        >
          Return to pipeline
        </Link>
      </div>
    );
  }

  const warningViolations = currentAsset.violations.filter((v) => v.severity === "WARNING" && !v.resolved);
  const isApproved = currentAsset.status === "APPROVED";
  const blockReason = approveBlockReason(currentAsset, claims);
  const canApprove = !isApproved && !blockReason;

  const content = currentAsset.content || {};
  const blocks: RenderedBlock[] = content.blocks || [];
  const slideCount = currentAsset.renderUrls.length || (content.slides || []).length;
  const currentSlide = (content.slides || []).find((s: any) => Number(s.n) === slideIdx);
  const paragraphs = content.paragraphs || [];
  const hashtags = content.hashtags || [];

  // Claim-clickable overlays over a 1080-wide render. Only copy blocks get one.
  const renderOverlays = (list: RenderedBlock[], height: number) =>
    list
      .filter((b) => isContentBlock(b.blockId))
      .map((block) => {
        const info = resolveBlock(content, block.blockId);
        const isSelected = selection?.blockId === block.blockId;
        return (
          <button
            key={block.blockId}
            data-testid="block-overlay"
            data-block-id={block.blockId}
            data-kind={info.kind}
            onClick={() => setSelection(info)}
            style={{
              position: "absolute",
              left: `${(block.x / 1080) * 100}%`,
              top: `${(block.y / height) * 100}%`,
              width: `${(block.w / 1080) * 100}%`,
              height: `${(block.h / height) * 100}%`,
            }}
            className={`group border transition-all rounded-sm cursor-pointer ${
              isSelected ? "border-rust bg-rust/20 ring-2 ring-rust" : "border-transparent hover:border-rust hover:bg-rust/10"
            }`}
            title={info.kind === "claim" ? `Show source for ${info.claimIds.join(", ")}` : `${info.kind} copy`}
          >
            <span className="opacity-0 group-hover:opacity-100 absolute -top-5 left-1 bg-ink text-cream text-[9px] px-1 py-0.5 rounded font-mono pointer-events-none whitespace-nowrap shadow">
              {info.kind}
            </span>
          </button>
        );
      });

  return (
    <div className="max-w-7xl mx-auto space-y-6 px-4 py-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4 border-b border-blush pb-4">
        <div className="flex items-center gap-3">
          <Link href={`/papers/${id}`} className="inline-flex items-center gap-1.5 text-xs text-ink/70 hover:text-ink font-medium">
            <ArrowLeft className="w-3.5 h-3.5" /> Pipeline
          </Link>
          <span className="text-blush">/</span>
          <h1 className="font-serif text-2xl text-ink">Gate 2: Asset review</h1>
          <span className="text-xs px-2 py-0.5 rounded bg-cream border border-blush font-mono text-ink/70">{id}</span>
        </div>
        <div className="flex items-center gap-3">
          <Link
            href={`/papers/${id}/claims`}
            className="px-3 py-1.5 rounded border border-blush bg-softPaper hover:bg-cream text-xs font-medium text-ink flex items-center gap-1.5"
          >
            <FileText className="w-3.5 h-3.5 text-rust" /> Claims console
          </Link>
          <Link
            href={`/papers/${id}/export`}
            className="px-3 py-1.5 rounded bg-maroon text-cream hover:bg-maroon/90 text-xs font-medium flex items-center gap-1.5"
          >
            <FileCheck className="w-3.5 h-3.5" /> Export gate
          </Link>
        </div>
      </div>

      {/* Tabs & actions */}
      <div className="bg-softPaper border border-blush rounded-lg p-4 flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
        <div className="flex items-center gap-2" role="tablist">
          {TABS.map((tab) => {
            const a = assets.find((x) => x.type === tab);
            const blockCount = a?.violations.filter((v) => v.severity === "BLOCKING" && !v.resolved).length || 0;
            return (
              <button
                key={tab}
                role="tab"
                data-testid={`tab-${tab.toLowerCase()}`}
                aria-selected={activeTab === tab}
                disabled={!a}
                onClick={() => {
                  setActiveTab(tab);
                  setSelection(null);
                }}
                className={`px-4 py-2 rounded text-sm font-medium transition flex items-center gap-2 disabled:opacity-40 ${
                  activeTab === tab ? "bg-rust text-cream shadow-sm" : "bg-cream text-ink/80 hover:bg-blush/40"
                }`}
              >
                <span>{tab.charAt(0) + tab.slice(1).toLowerCase()}</span>
                {a?.status === "APPROVED" && (
                  <CheckCircle className={`w-3.5 h-3.5 ${activeTab === tab ? "text-cream" : "text-emerald-700"}`} />
                )}
                {blockCount > 0 && <span className="w-2 h-2 rounded-full bg-red-600" title="Has blocking violations" />}
              </button>
            );
          })}
        </div>

        <div className="flex flex-col items-end gap-1.5">
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-2 text-xs">
              <span className="text-ink/60">Status:</span>
              <span
                data-testid="asset-status"
                className={`px-2 py-0.5 rounded font-mono font-bold text-[11px] border ${
                  currentAsset.status === "APPROVED"
                    ? "bg-emerald-100 text-emerald-800 border-emerald-300"
                    : currentAsset.status === "BLOCKED"
                    ? "bg-red-100 text-red-800 border-red-300"
                    : currentAsset.status === "STALE"
                    ? "bg-amber-100 text-amber-800 border-amber-300"
                    : "bg-cream text-ink border-blush"
                }`}
              >
                {currentAsset.status}
              </span>
              {currentAsset.approvedBy && <span className="text-ink/60 text-[11px] ml-1">by {currentAsset.approvedBy}</span>}
            </div>

            <button
              onClick={handleRegenerate}
              disabled={regenerating || isApproved}
              className="px-3 py-1.5 rounded border border-blush bg-cream text-xs font-medium text-ink hover:bg-softPaper flex items-center gap-1.5 disabled:opacity-50"
              title="Re-run writer, policy check and render for this asset"
            >
              <RotateCw className={`w-3.5 h-3.5 ${regenerating ? "animate-spin" : ""}`} />
              Regenerate
            </button>

            {/* Disabled buttons swallow hover events, so the tooltip sits on a wrapper */}
            <span title={blockReason || (isApproved ? "Already approved" : "Approve this asset")} data-testid="approve-wrapper">
              <button
                data-testid="approve-asset"
                onClick={() => handleApprove(false)}
                disabled={!canApprove || approving}
                className={`px-4 py-1.5 rounded text-xs font-semibold flex items-center gap-1.5 shadow-sm transition ${
                  isApproved
                    ? "bg-emerald-700 text-cream cursor-default"
                    : canApprove
                    ? "bg-rust text-cream hover:bg-rust/90 cursor-pointer"
                    : "bg-clay/40 text-ink/40 cursor-not-allowed pointer-events-none"
                }`}
              >
                <CheckCircle className="w-3.5 h-3.5" />
                {isApproved ? "Approved ✓" : approving ? "Approving..." : "Approve asset"}
              </button>
            </span>
          </div>
          {blockReason && (
            <p className="text-[11px] text-red-800 max-w-md text-right" data-testid="approve-block-reason">
              {blockReason}
            </p>
          )}
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        {/* Preview */}
        <div className="lg:col-span-7 bg-softPaper border border-blush rounded-lg p-5 shadow-sm space-y-4">
          {activeTab === "CAROUSEL" && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-medium text-ink/70">
                    Slide {slideIdx} of {slideCount}
                  </span>
                  {currentSlide?.kind && (
                    <span className={`text-[10px] font-mono px-2 py-0.5 rounded border ${kindBadgeClass(currentSlide.kind)}`}>
                      {currentSlide.kind}
                    </span>
                  )}
                </div>
                <div className="flex items-center gap-2">
                  <button
                    data-testid="slide-prev"
                    onClick={() => {
                      setSlideIdx((p) => Math.max(1, p - 1));
                      setSelection(null);
                    }}
                    disabled={slideIdx <= 1}
                    className="p-1 rounded bg-cream border border-blush text-ink disabled:opacity-30 hover:bg-softPaper"
                    aria-label="Previous slide"
                  >
                    <ChevronLeft className="w-4 h-4" />
                  </button>
                  <span className="text-xs font-mono font-medium">{slideIdx}</span>
                  <button
                    data-testid="slide-next"
                    onClick={() => {
                      setSlideIdx((p) => Math.min(slideCount, p + 1));
                      setSelection(null);
                    }}
                    disabled={slideIdx >= slideCount}
                    className="p-1 rounded bg-cream border border-blush text-ink disabled:opacity-30 hover:bg-softPaper"
                    aria-label="Next slide"
                  >
                    <ChevronRight className="w-4 h-4" />
                  </button>
                </div>
              </div>

              <div className="relative mx-auto max-w-[440px] aspect-[4/5] bg-cream rounded-md overflow-hidden border border-blush shadow-inner">
                {currentAsset.renderUrls[slideIdx - 1] ? (
                  <img
                    src={fileUrl(currentAsset.renderUrls[slideIdx - 1])}
                    alt={`Slide ${slideIdx}`}
                    className="w-full h-full object-contain pointer-events-none select-none"
                  />
                ) : (
                  <div className="w-full h-full flex items-center justify-center text-ink/40 text-sm">Not rendered yet</div>
                )}
                {renderOverlays(
                  blocks.filter((b) => b.slide === slideIdx || b.image === `carousel/${slideIdx}.png`),
                  1350
                )}
              </div>

              <div className="flex justify-center items-center gap-1.5 pt-2">
                {Array.from({ length: slideCount }).map((_, i) => (
                  <button
                    key={i}
                    data-testid={`slide-dot-${i + 1}`}
                    onClick={() => {
                      setSlideIdx(i + 1);
                      setSelection(null);
                    }}
                    aria-label={`Slide ${i + 1}`}
                    className={`h-2 rounded-full transition-all ${slideIdx === i + 1 ? "w-6 bg-rust" : "w-2 bg-clay/50 hover:bg-clay"}`}
                  />
                ))}
              </div>
            </div>
          )}

          {activeTab === "STATIC" && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium text-ink/70">
                  {staticRatioIdx === 0 ? "Portrait 4:5 (1080×1350)" : staticRatioIdx === 1 ? "Square 1:1 (1080×1080)" : "Story 9:16 (1080×1920)"}
                </span>
                <div className="flex items-center gap-1">
                  {currentAsset.renderUrls.map((_, idx) => (
                    <button
                      key={idx}
                      onClick={() => {
                        setStaticRatioIdx(idx);
                        setSelection(null);
                      }}
                      className={`px-2.5 py-1 text-xs rounded border transition ${
                        staticRatioIdx === idx ? "bg-rust text-cream border-rust" : "bg-cream border-blush text-ink/80 hover:bg-softPaper"
                      }`}
                    >
                      {idx === 0 ? "4:5" : idx === 1 ? "1:1" : "9:16"}
                    </button>
                  ))}
                </div>
              </div>
              <div
                className={`relative mx-auto max-w-[440px] bg-cream rounded-md overflow-hidden border border-blush shadow-inner ${
                  staticRatioIdx === 1 ? "aspect-square" : staticRatioIdx === 2 ? "aspect-[9/16]" : "aspect-[4/5]"
                }`}
              >
                {currentAsset.renderUrls[staticRatioIdx] ? (
                  <img
                    src={fileUrl(currentAsset.renderUrls[staticRatioIdx])}
                    alt="Static post"
                    className="w-full h-full object-contain pointer-events-none select-none"
                  />
                ) : (
                  <div className="w-full h-full flex items-center justify-center text-ink/40 text-sm">No render available</div>
                )}
                {/* Block coordinates are measured on the 4:5 render */}
                {staticRatioIdx === 0 && renderOverlays(blocks, 1350)}
              </div>
              {staticRatioIdx !== 0 && (
                <p className="text-[11px] text-ink/50 text-center">Switch to 4:5 to click through to sources.</p>
              )}
            </div>
          )}

          {activeTab === "REEL" && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium text-ink/70">Reel 9:16 with voiceover</span>
                <span className="text-xs font-mono text-ink/60" data-testid="video-time">
                  {videoTime.toFixed(1)}s{videoDuration ? ` / ${videoDuration.toFixed(1)}s` : ""}
                </span>
              </div>
              <div className="relative mx-auto max-w-[320px] aspect-[9/16] bg-ink rounded-lg overflow-hidden border border-blush shadow-lg">
                {currentAsset.renderUrls[0] ? (
                  <video
                    ref={videoRef}
                    data-testid="reel-video"
                    src={fileUrl(currentAsset.renderUrls[0])}
                    controls
                    playsInline
                    preload="metadata"
                    onLoadedMetadata={(e) => setVideoDuration((e.target as HTMLVideoElement).duration)}
                    onTimeUpdate={(e) => setVideoTime((e.target as HTMLVideoElement).currentTime)}
                    onSeeked={(e) => setVideoTime((e.target as HTMLVideoElement).currentTime)}
                    className="w-full h-full object-cover"
                  />
                ) : (
                  <div className="w-full h-full flex items-center justify-center text-cream/50 text-sm">Not rendered yet</div>
                )}
              </div>

              <div className="space-y-2 pt-2 border-t border-blush">
                <h3 className="text-xs font-bold text-ink/60">Scenes ({scenes.length})</h3>
                <div className="space-y-2 max-h-72 overflow-y-auto pr-1">
                  {scenes.map((scene: any, idx: number) => {
                    const span = spans?.find((s) => s.n === Number(scene.n));
                    const isPlaying = activeScene === Number(scene.n);
                    const isSelected = selection?.blockId === `scene${scene.n}.onScreen`;
                    return (
                      <button
                        key={idx}
                        data-testid="reel-scene"
                        data-scene={scene.n}
                        data-start={span?.start ?? ""}
                        data-active={isPlaying ? "true" : "false"}
                        onClick={() => selectScene(scene, true)}
                        className={`w-full text-left p-2.5 rounded border text-xs transition cursor-pointer flex flex-col gap-1 ${
                          isPlaying
                            ? "bg-rust/10 border-rust ring-1 ring-rust/40"
                            : isSelected
                            ? "bg-cream border-rust/60"
                            : "bg-cream border-blush hover:bg-softPaper"
                        }`}
                      >
                        <span className="flex items-center justify-between gap-2">
                          <span className="font-bold text-ink flex items-center gap-1.5">
                            <span className="w-4 h-4 rounded-full bg-rust text-cream text-[10px] inline-flex items-center justify-center shrink-0">
                              {scene.n}
                            </span>
                            {plainText(scene.onScreen)}
                          </span>
                          <span className="flex items-center gap-1.5 shrink-0">
                            {span && (
                              <span className="text-[10px] font-mono text-ink/50">
                                {span.start.toFixed(1)}–{span.end.toFixed(1)}s
                              </span>
                            )}
                            <span className={`text-[10px] font-mono px-1.5 py-0.5 rounded border ${kindBadgeClass(scene.kind)}`}>
                              {scene.kind}
                            </span>
                          </span>
                        </span>
                        <span className="block text-ink/80 italic text-[11px] leading-relaxed">“{scene.voiceover}”</span>
                        <span className="block text-[10px] text-ink/50">
                          {scene.creditLine ? (
                            <>B-roll: {scene.creditLine}</>
                          ) : scene.brollQuery ? (
                            <>
                              B-roll query <span className="font-mono">{scene.brollQuery}</span>: no clip used
                            </>
                          ) : null}
                        </span>
                      </button>
                    );
                  })}
                </div>
              </div>
            </div>
          )}

          {activeTab === "CAPTION" && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium text-ink/70">Post caption</span>
                <button
                  onClick={handleCopyCaption}
                  className="px-3 py-1.5 rounded bg-rust text-cream text-xs font-medium hover:bg-rust/90 flex items-center gap-1.5 shadow-sm"
                >
                  <Copy className="w-3.5 h-3.5" /> Copy caption
                </button>
              </div>
              <div className="bg-cream border border-blush rounded-lg p-5 space-y-3 text-sm leading-relaxed text-ink shadow-inner">
                {paragraphs.map((p: any, idx: number) => {
                  const kind: BlockKind = p.kind === "claim" || p.kind === "illustration" ? p.kind : "brand";
                  const blockId = `caption.p${idx + 1}`;
                  return (
                    <button
                      key={idx}
                      data-testid="caption-paragraph"
                      onClick={() =>
                        setSelection({ blockId, kind, claimIds: kind === "claim" ? p.claimIds || [] : [], text: p.text })
                      }
                      className={`w-full text-left rounded p-2 -m-2 transition border ${
                        selection?.blockId === blockId ? "border-rust bg-rust/10" : "border-transparent hover:border-blush hover:bg-softPaper"
                      }`}
                    >
                      <span className="block">{p.text}</span>
                      <span className="flex items-center gap-1.5 pt-1">
                        <span className={`text-[10px] font-mono px-1.5 py-0.5 rounded border ${kindBadgeClass(kind)}`}>{kind}</span>
                        {kind === "claim" &&
                          (p.claimIds || []).map((cid: string) => (
                            <span key={cid} className="text-[10px] font-mono text-rust">
                              {cid}
                            </span>
                          ))}
                      </span>
                    </button>
                  );
                })}
                {hashtags.length > 0 && (
                  <div className="pt-3 border-t border-blush/60 flex flex-wrap gap-1.5">
                    {hashtags.map((ht: string, idx: number) => (
                      <span key={idx} className="text-xs font-mono text-rust bg-softPaper px-2 py-0.5 rounded border border-blush">
                        {ht}
                      </span>
                    ))}
                  </div>
                )}
              </div>
            </div>
          )}
        </div>

        {/* Right column */}
        <div className="lg:col-span-5 space-y-4">
          <SourceDrawer paperId={id} selection={selection} claims={claims} onClose={() => setSelection(null)} />

          <div className="bg-softPaper border border-blush rounded-lg p-4 shadow-sm space-y-3">
            <div className="flex items-center justify-between">
              <h3 className="text-xs font-bold uppercase tracking-wider text-ink/70 flex items-center gap-1.5">
                <FileCheck className="w-3.5 h-3.5 text-rust" /> Policy check
              </h3>
              <span className="text-xs font-mono">{currentAsset.violations.length} issue(s)</span>
            </div>
            {currentAsset.violations.length === 0 ? (
              <div className="bg-emerald-50 border border-emerald-200 rounded p-3 text-xs text-emerald-800 flex items-center gap-2">
                <CheckCircle className="w-4 h-4 text-emerald-600 flex-shrink-0" />
                <span>No policy violations recorded for this asset.</span>
              </div>
            ) : (
              <div className="space-y-2" data-testid="violations">
                {currentAsset.violations.map((v, idx) => (
                  <div
                    key={idx}
                    className={`p-2.5 rounded border text-xs space-y-1 ${
                      v.resolved
                        ? "bg-cream border-blush text-ink/60"
                        : v.severity === "BLOCKING"
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
                        {v.rule}
                      </span>
                      <span className="text-[10px] font-mono font-bold">
                        {v.severity}
                        {v.resolved ? " · overridden" : ""}
                      </span>
                    </div>
                    {v.detail && <p className="text-[11px] leading-tight">{v.detail}</p>}
                    {(v.location || v.claimId) && (
                      <div className="text-[10px] text-ink/60 font-mono">
                        {v.location}
                        {v.claimId ? ` · ${v.claimId}` : ""}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>

          <div className="bg-softPaper border border-blush rounded-lg p-4 shadow-sm space-y-3">
            <h3 className="text-xs font-bold uppercase tracking-wider text-ink/70 flex items-center gap-1.5">
              <Eye className="w-3.5 h-3.5 text-rust" /> Writer intent
            </h3>
            {content.coreMessage && (
              <div>
                <span className="text-[11px] font-semibold text-ink/60">Core message</span>
                <p className="text-xs font-medium text-ink mt-0.5 leading-snug">{content.coreMessage}</p>
              </div>
            )}
            {content.reasoning && (
              <div>
                <span className="text-[11px] font-semibold text-ink/60">Reasoning</span>
                <p className="text-xs text-ink/80 mt-0.5 leading-relaxed bg-cream p-2.5 rounded border border-blush/60">
                  {content.reasoning}
                </p>
              </div>
            )}
          </div>
        </div>
      </div>

      {showOverrideModal && (
        <div className="fixed inset-0 bg-ink/50 flex items-center justify-center p-4 z-50" role="dialog" aria-modal="true">
          <div className="bg-cream border border-blush rounded-lg max-w-md w-full p-6 space-y-4 shadow-xl">
            <div className="flex items-center gap-3 text-amber-700">
              <AlertTriangle className="w-6 h-6 flex-shrink-0" />
              <h3 className="font-serif text-lg text-ink">Approve with warnings?</h3>
            </div>
            <p className="text-xs text-ink/80 leading-relaxed">
              This asset has {warningViolations.length} warning(s). Approving overrides them, and the override is written to
              the audit log under your name.
            </p>
            <div className="max-h-36 overflow-y-auto space-y-1.5 p-2 bg-softPaper rounded border border-blush text-xs">
              {warningViolations.map((w, idx) => (
                <div key={idx} className="text-ink/80">
                  <span className="font-mono font-bold text-amber-800">{w.rule}:</span> {w.detail}
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
                className="px-4 py-1.5 rounded bg-rust text-cream text-xs font-medium hover:bg-rust/90 shadow-sm"
              >
                {approving ? "Approving..." : "Override and approve"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
