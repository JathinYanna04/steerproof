"use client";

import React, { useEffect, useState, useRef } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { apiRequest, showToast, API_BASE } from "@/lib/api";
import { useReviewer } from "@/components/ReviewerContext";

interface Passage {
  passageId: string;
  page: number;
  section?: string;
  pageWidth?: number;
  pageHeight?: number;
  bbox?: { l: number; t: number; r: number; b: number };
  text?: string;
}

interface Claim {
  claimId: string;
  paperId: string;
  passageId?: string;
  scientificClaim: string;
  consumerDraft: string;
  quote: string;
  numbersOk?: boolean;
  quoteScore?: number;
  entailment?: string;
  risk?: string;
  riskReason?: string;
  populationFit?: string;
  populationReason?: string;
  findingClass?: string;
  limitations?: string[];
  doctorStatus: string;
  editedCopy?: string;
  doctorNote?: string;
  doctorName?: string;
  reviewSeconds?: number;
  passage?: Passage;
}

export default function ClaimsReviewPage() {
  const params = useParams();
  const router = useRouter();
  const paperId = params.id as string;
  const { reviewerName } = useReviewer();

  const [claims, setClaims] = useState<Claim[]>([]);
  const [passages, setPassages] = useState<Passage[]>([]);
  const [selectedClaimId, setSelectedClaimId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [generating, setGenerating] = useState(false);

  // Edit modal
  const [editingClaim, setEditingClaim] = useState<Claim | null>(null);
  const [editText, setEditText] = useState("");
  const [editPassageId, setEditPassageId] = useState("");

  // Reject modal
  const [rejectingClaim, setRejectingClaim] = useState<Claim | null>(null);
  const [rejectNote, setRejectNote] = useState("");

  // Review timer
  const cardFocusTimeRef = useRef<number>(Date.now());
  const imgRef = useRef<HTMLImageElement>(null);
  const [imgNaturalWidth, setImgNaturalWidth] = useState<number>(595);
  const [imgRenderedWidth, setImgRenderedWidth] = useState<number>(595);

  useEffect(() => {
    loadData();
  }, [paperId]);

  // Reset focus timer on claim change
  useEffect(() => {
    cardFocusTimeRef.current = Date.now();
  }, [selectedClaimId]);

  const loadData = async () => {
    setLoading(true);
    try {
      const claimsData = await apiRequest<Claim[]>(`/api/papers/${paperId}/claims`);
      setClaims(claimsData);
      if (claimsData.length > 0 && !selectedClaimId) {
        setSelectedClaimId(claimsData[0].claimId);
      }

      const passagesData = await apiRequest<Passage[]>(`/api/papers/${paperId}/passages`).catch(() => []);
      setPassages(passagesData);
    } catch (e: any) {
      showToast(e.message || "Failed to load claims", "error");
    } finally {
      setLoading(false);
    }
  };

  // Keyboard navigation: j/k, a, e, r
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      // Don't trigger shortcuts if inside textarea/input
      if (["INPUT", "TEXTAREA", "SELECT"].includes((e.target as HTMLElement)?.tagName)) {
        return;
      }

      if (!sortedClaims.length) return;
      const curIdx = sortedClaims.findIndex((c) => c.claimId === selectedClaimId);

      if (e.key === "j" || e.key === "ArrowDown") {
        e.preventDefault();
        const next = Math.min(sortedClaims.length - 1, curIdx + 1);
        setSelectedClaimId(sortedClaims[next].claimId);
      } else if (e.key === "k" || e.key === "ArrowUp") {
        e.preventDefault();
        const prev = Math.max(0, curIdx - 1);
        setSelectedClaimId(sortedClaims[prev].claimId);
      } else if (e.key === "a") {
        e.preventDefault();
        const c = sortedClaims[curIdx];
        if (c && c.doctorStatus !== "NEEDS_EVIDENCE") {
          handleApprove(c.claimId);
        }
      } else if (e.key === "e") {
        e.preventDefault();
        const c = sortedClaims[curIdx];
        if (c) openEditModal(c);
      } else if (e.key === "r") {
        e.preventDefault();
        const c = sortedClaims[curIdx];
        if (c) openRejectModal(c);
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [claims, selectedClaimId, reviewerName]);

  const getFocusSeconds = () => {
    const sec = Math.max(1, Math.round((Date.now() - cardFocusTimeRef.current) / 1000));
    cardFocusTimeRef.current = Date.now();
    return sec;
  };

  // Sort claims: RED -> AMBER -> GREEN, then findingClass
  const sortedClaims = [...claims].sort((a, b) => {
    const riskRank = (r?: string) => (r === "RED" ? 0 : r === "AMBER" ? 1 : 2);
    const diff = riskRank(a.risk) - riskRank(b.risk);
    if (diff !== 0) return diff;
    return (a.claimId || "").localeCompare(b.claimId || "");
  });

  const selectedClaim = claims.find((c) => c.claimId === selectedClaimId) || sortedClaims[0];

  const handleApprove = async (claimId: string) => {
    const sec = getFocusSeconds();
    try {
      const res = await apiRequest<{ claim: Claim; staleAssets: string[] }>(`/api/claims/${claimId}`, {
        method: "PATCH",
        body: JSON.stringify({
          action: "APPROVE",
          doctorName: reviewerName,
          reviewSeconds: sec,
        }),
      });
      showToast(`Claim ${claimId} approved!`, "success");
      setClaims((prev) => prev.map((c) => (c.claimId === claimId ? { ...c, ...res.claim } : c)));
    } catch (e: any) {
      showToast(e.message || "Approval failed", "error");
    }
  };

  const openEditModal = (claim: Claim) => {
    setEditingClaim(claim);
    setEditText(claim.editedCopy || claim.consumerDraft);
    setEditPassageId(claim.passageId || "");
  };

  const handleSaveEdit = async () => {
    if (!editingClaim) return;
    const sec = getFocusSeconds();
    try {
      const res = await apiRequest<{ claim: Claim; staleAssets: string[] }>(`/api/claims/${editingClaim.claimId}`, {
        method: "PATCH",
        body: JSON.stringify({
          action: "EDIT",
          doctorName: reviewerName,
          editedCopy: editText.trim(),
          passageId: editPassageId || undefined,
          reviewSeconds: sec,
        }),
      });
      showToast(`Claim ${editingClaim.claimId} edited & re-verified!`, "success");
      setClaims((prev) => prev.map((c) => (c.claimId === editingClaim.claimId ? { ...c, ...res.claim } : c)));
      setEditingClaim(null);
    } catch (e: any) {
      showToast(e.message || "Edit failed", "error");
    }
  };

  const openRejectModal = (claim: Claim) => {
    setRejectingClaim(claim);
    setRejectNote(claim.doctorNote || "");
  };

  const handleSaveReject = async () => {
    if (!rejectingClaim) return;
    if (!rejectNote.trim()) {
      showToast("A rejection note is required", "error");
      return;
    }
    const sec = getFocusSeconds();
    try {
      const res = await apiRequest<{ claim: Claim; staleAssets: string[] }>(`/api/claims/${rejectingClaim.claimId}`, {
        method: "PATCH",
        body: JSON.stringify({
          action: "REJECT",
          doctorName: reviewerName,
          note: rejectNote.trim(),
          reviewSeconds: sec,
        }),
      });
      showToast(`Claim ${rejectingClaim.claimId} rejected`, "info");
      setClaims((prev) => prev.map((c) => (c.claimId === rejectingClaim.claimId ? { ...c, ...res.claim } : c)));
      setRejectingClaim(null);
    } catch (e: any) {
      showToast(e.message || "Rejection failed", "error");
    }
  };

  const handleGenerateContent = async () => {
    setGenerating(true);
    try {
      await apiRequest(`/api/papers/${paperId}/generate`, { method: "POST" });
      showToast("Content generation initiated! Redirecting to assets...", "success");
      router.push(`/papers/${paperId}/assets`);
    } catch (e: any) {
      // 422 shows blockers verbatim
      showToast(e.message || "Gate 1 Check Failed", "error");
    } finally {
      setGenerating(false);
    }
  };

  // Reviewed count
  const reviewedCount = claims.filter((c) => ["APPROVED", "EDITED", "REJECTED"].includes(c.doctorStatus)).length;
  const totalCount = claims.length;

  return (
    <div className="flex flex-col h-[calc(100vh-64px)] overflow-hidden">
      {/* Top Bar */}
      <div className="bg-softPaper border-b border-blush px-6 py-3 flex justify-between items-center z-20">
        <div className="flex items-center gap-3">
          <Link href={`/papers/${paperId}`} className="text-xs text-clay hover:text-rust">
            ← Pipeline
          </Link>
          <span className="text-xs text-clay">|</span>
          <h1 className="font-serif text-xl text-ink font-normal">Gate 1: Clinical Claims Review</h1>
          <span className="text-xs font-mono px-2 py-0.5 rounded bg-cream border border-blush text-clay">
            {reviewedCount} / {totalCount} reviewed
          </span>
        </div>

        <div className="flex items-center gap-3">
          <span className="hidden sm:inline text-xs text-clay">
            Shortcuts: <kbd className="px-1.5 py-0.5 bg-cream border border-blush rounded text-[10px]">j/k</kbd> navigate,{" "}
            <kbd className="px-1.5 py-0.5 bg-cream border border-blush rounded text-[10px]">a</kbd> approve,{" "}
            <kbd className="px-1.5 py-0.5 bg-cream border border-blush rounded text-[10px]">e</kbd> edit,{" "}
            <kbd className="px-1.5 py-0.5 bg-cream border border-blush rounded text-[10px]">r</kbd> reject
          </span>
          <button
            onClick={handleGenerateContent}
            disabled={generating}
            className="px-4 py-2 bg-rust text-cream text-xs font-medium rounded-lg hover:bg-[#A84222] transition-colors disabled:opacity-50 shadow-sm"
          >
            {generating ? "Verifying Gate 1..." : "Generate Content →"}
          </button>
        </div>
      </div>

      {/* Split Console */}
      <div className="flex-1 flex overflow-hidden">
        {/* Left Column: Claim Cards (40%) */}
        <div className="w-full md:w-[45%] lg:w-[40%] border-r border-blush overflow-y-auto p-4 space-y-3 bg-cream/30">
          {loading ? (
            <div className="p-8 text-center text-clay text-sm">Loading claims...</div>
          ) : (
            sortedClaims.map((c) => {
              const isSelected = c.claimId === selectedClaim?.claimId;
              const isReviewed = ["APPROVED", "EDITED", "REJECTED"].includes(c.doctorStatus);

              return (
                <div
                  key={c.claimId}
                  onClick={() => setSelectedClaimId(c.claimId)}
                  className={`p-4 rounded-xl border transition-all cursor-pointer ${
                    isSelected
                      ? "bg-softPaper border-rust shadow-md ring-1 ring-rust/30"
                      : "bg-softPaper/70 border-blush hover:border-clay hover:bg-softPaper"
                  }`}
                >
                  {/* Card Header & Badges */}
                  <div className="flex justify-between items-start gap-2 mb-2">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className="text-xs font-mono font-medium text-ink">{c.claimId}</span>

                      {/* Finding Class */}
                      <span className="text-[10px] px-2 py-0.5 rounded bg-cream border border-blush text-clay uppercase">
                        {c.findingClass || "PRIMARY"}
                      </span>

                      {/* Risk Dot */}
                      <div className="flex items-center gap-1 text-[11px] px-2 py-0.5 rounded bg-cream border border-blush" title={c.riskReason}>
                        <span
                          className={`w-2 h-2 rounded-full ${
                            c.risk === "RED"
                              ? "bg-red-600"
                              : c.risk === "AMBER"
                              ? "bg-amber-500"
                              : "bg-emerald-600"
                          }`}
                        />
                        <span className="text-clay font-medium">{c.risk || "GREEN"}</span>
                      </div>

                      {/* Population Fit */}
                      {c.populationFit && (
                        <span className="text-[10px] px-1.5 py-0.5 rounded bg-cream border border-blush text-clay" title={c.populationReason}>
                          Pop: {c.populationFit}
                        </span>
                      )}

                      {/* Needs Evidence Alert */}
                      {c.doctorStatus === "NEEDS_EVIDENCE" && (
                        <span className="text-[10px] font-bold px-1.5 py-0.5 rounded bg-red-100 text-red-800 border border-red-300">
                          NEEDS EVIDENCE
                        </span>
                      )}
                    </div>

                    {/* Status Badge */}
                    <span
                      className={`text-[10px] uppercase font-semibold px-2 py-0.5 rounded-full border ${
                        c.doctorStatus === "APPROVED"
                          ? "bg-emerald-950/10 text-emerald-800 border-emerald-400"
                          : c.doctorStatus === "EDITED"
                          ? "bg-blue-950/10 text-blue-800 border-blue-400"
                          : c.doctorStatus === "REJECTED"
                          ? "bg-red-950/10 text-red-800 border-red-400"
                          : "bg-blush/40 text-clay border-blush"
                      }`}
                    >
                      {c.doctorStatus}
                    </span>
                  </div>

                  {/* Scientific Claim */}
                  <h4 className="text-xs font-semibold text-ink leading-snug mb-1.5">
                    {c.scientificClaim}
                  </h4>

                  {/* Consumer Draft */}
                  <div className="text-xs text-clay leading-relaxed mb-3 italic">
                    "{c.editedCopy || c.consumerDraft}"
                  </div>

                  {/* Verification Metrics Line */}
                  <div className="flex items-center gap-3 text-[11px] text-clay mb-3 pt-2 border-t border-blush/60">
                    <span>
                      Quote Match: <strong className="text-ink">{c.quoteScore || 100}%</strong>
                    </span>
                    <span>
                      Numbers:{" "}
                      <strong className={c.numbersOk === false ? "text-red-700" : "text-emerald-800"}>
                        {c.numbersOk === false ? "✗ Mismatch" : "✓ Match"}
                      </strong>
                    </span>
                    {c.entailment && (
                      <span>
                        Entailment: <strong className="text-ink">{c.entailment}</strong>
                      </span>
                    )}
                  </div>

                  {/* Review Buttons */}
                  <div className="flex justify-end gap-2 pt-1" onClick={(e) => e.stopPropagation()}>
                    <button
                      onClick={() => handleApprove(c.claimId)}
                      disabled={c.doctorStatus === "NEEDS_EVIDENCE"}
                      className="px-3 py-1 bg-emerald-800 hover:bg-emerald-900 text-cream text-xs font-medium rounded transition-colors disabled:opacity-40"
                    >
                      Approve
                    </button>
                    <button
                      onClick={() => openEditModal(c)}
                      className="px-3 py-1 bg-cream hover:bg-blush/50 text-ink border border-blush text-xs font-medium rounded transition-colors"
                    >
                      Edit
                    </button>
                    <button
                      onClick={() => openRejectModal(c)}
                      className="px-3 py-1 bg-cream hover:bg-red-50 text-red-800 border border-red-200 text-xs font-medium rounded transition-colors"
                    >
                      Reject
                    </button>
                  </div>
                </div>
              );
            })
          )}
        </div>

        {/* Right Column: PDF Source Viewer (60%) */}
        <div className="hidden md:flex flex-1 flex-col overflow-y-auto p-6 bg-cream">
          {selectedClaim ? (
            <div className="max-w-2xl mx-auto w-full space-y-4">
              {/* Evidence Section Label */}
              <div className="bg-softPaper border border-blush rounded-xl p-4 shadow-sm">
                <div className="flex justify-between items-center mb-1">
                  <span className="text-xs uppercase tracking-wider text-clay font-medium">
                    Evidence Passage · Page {selectedClaim.passage?.page || 1}
                  </span>
                  <span className="text-xs font-mono text-rust">
                    {selectedClaim.passage?.passageId || selectedClaim.passageId}
                  </span>
                </div>
                <div className="text-sm font-medium text-ink mb-1">
                  Section: {selectedClaim.passage?.section || "Clinical Findings"}
                </div>
                <p className="text-xs text-clay leading-relaxed">
                  {selectedClaim.passage?.text || selectedClaim.quote}
                </p>
              </div>

              {/* Page PNG with Highlight Overlay */}
              <div className="bg-softPaper border border-blush rounded-xl p-4 shadow-sm flex flex-col items-center">
                <div className="text-xs text-clay self-start mb-2 font-medium">
                  Page {selectedClaim.passage?.page || 1} Layout & Bounding Box
                </div>

                <div className="relative border border-blush rounded-md overflow-hidden bg-white shadow-inner">
                  <img
                    ref={imgRef}
                    src={`${API_BASE}/files/pages/${paperId}/${selectedClaim.passage?.page || 1}.png`}
                    alt={`Page ${selectedClaim.passage?.page || 1}`}
                    className="max-h-[700px] w-auto object-contain block"
                    onLoad={(e) => {
                      const target = e.currentTarget;
                      setImgNaturalWidth(target.naturalWidth || 595);
                      setImgRenderedWidth(target.clientWidth || 595);
                    }}
                  />

                  {/* Rust Translucent Highlight Box */}
                  {selectedClaim.passage?.bbox && (
                    <div
                      style={(() => {
                        const bbox = selectedClaim.passage.bbox;
                        const pageWidth = selectedClaim.passage.pageWidth || imgNaturalWidth || 595;
                        const scale = (imgRef.current?.clientWidth || imgRenderedWidth) / pageWidth;
                        return {
                          position: "absolute",
                          left: `${bbox.l * scale}px`,
                          top: `${bbox.t * scale}px`,
                          width: `${(bbox.r - bbox.l) * scale}px`,
                          height: `${(bbox.b - bbox.t) * scale}px`,
                          backgroundColor: "rgba(192, 78, 42, 0.28)",
                          border: "2px solid #C04E2A",
                          borderRadius: "3px",
                          pointerEvents: "none",
                        };
                      })()}
                    />
                  )}
                </div>
              </div>
            </div>
          ) : (
            <div className="flex-1 flex items-center justify-center text-clay text-sm">
              Select a claim card on the left to inspect its evidence source.
            </div>
          )}
        </div>
      </div>

      {/* Edit Modal */}
      {editingClaim && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4">
          <div className="bg-softPaper border border-blush rounded-xl p-6 max-w-lg w-full shadow-xl">
            <h3 className="font-serif text-2xl text-ink font-normal mb-1">
              Edit Copy: {editingClaim.claimId}
            </h3>
            <p className="text-xs text-clay mb-4">
              Edit the consumer draft to align strictly with clinical evidence. Saving triggers immediate re-verification.
            </p>

            <div className="mb-4">
              <label className="block text-xs uppercase tracking-wider text-clay font-medium mb-1">
                Consumer Draft Copy
              </label>
              <textarea
                rows={3}
                value={editText}
                onChange={(e) => setEditText(e.target.value)}
                className="w-full px-3 py-2 text-xs border border-blush rounded-lg bg-cream text-ink focus:outline-none focus:border-rust"
              />
            </div>

            <div className="mb-6">
              <label className="block text-xs uppercase tracking-wider text-clay font-medium mb-1">
                Optional: Re-link Evidence Passage (Page {editingClaim.passage?.page || 1})
              </label>
              <select
                value={editPassageId}
                onChange={(e) => setEditPassageId(e.target.value)}
                className="w-full px-3 py-2 text-xs border border-blush rounded-lg bg-cream text-ink focus:outline-none focus:border-rust"
              >
                <option value="">Keep current passage ({editingClaim.passageId})</option>
                {passages
                  .filter((p) => !editingClaim.passage?.page || p.page === editingClaim.passage.page)
                  .map((p) => (
                    <option key={p.passageId} value={p.passageId}>
                      {p.passageId} (Page {p.page} · {p.section || "Text"}) - {p.text?.slice(0, 60)}...
                    </option>
                  ))}
              </select>
            </div>

            <div className="flex justify-end gap-2">
              <button
                onClick={() => setEditingClaim(null)}
                className="px-4 py-2 text-xs text-clay hover:text-ink font-medium"
              >
                Cancel
              </button>
              <button
                onClick={handleSaveEdit}
                className="px-4 py-2 bg-rust text-cream text-xs font-medium rounded-lg hover:bg-[#A84222]"
              >
                Save & Re-verify
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Reject Modal */}
      {rejectingClaim && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4">
          <div className="bg-softPaper border border-blush rounded-xl p-6 max-w-md w-full shadow-xl">
            <h3 className="font-serif text-2xl text-ink font-normal mb-1">
              Reject Claim: {rejectingClaim.claimId}
            </h3>
            <p className="text-xs text-clay mb-4">
              A clinical doctor note is required when rejecting a study claim.
            </p>

            <div className="mb-6">
              <label className="block text-xs uppercase tracking-wider text-clay font-medium mb-1">
                Rejection Note / Clinical Reason
              </label>
              <textarea
                rows={3}
                placeholder="State clinical reason for rejecting this claim..."
                value={rejectNote}
                onChange={(e) => setRejectNote(e.target.value)}
                className="w-full px-3 py-2 text-xs border border-blush rounded-lg bg-cream text-ink focus:outline-none focus:border-rust"
              />
            </div>

            <div className="flex justify-end gap-2">
              <button
                onClick={() => setRejectingClaim(null)}
                className="px-4 py-2 text-xs text-clay hover:text-ink font-medium"
              >
                Cancel
              </button>
              <button
                onClick={handleSaveReject}
                className="px-4 py-2 bg-red-800 text-cream text-xs font-medium rounded-lg hover:bg-red-900"
              >
                Confirm Reject
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
