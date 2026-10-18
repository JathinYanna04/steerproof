"use client";

import React, { useEffect, useState } from "react";
import Link from "next/link";
import { ExternalLink, FileText, X } from "lucide-react";
import { apiRequest } from "@/lib/api";
import { plainText, type Selection } from "@/lib/assets";
import { PageHighlight, type BBox } from "@/components/PageHighlight";

export interface ClaimRecord {
  claimId: string;
  paperId: string;
  scientificClaim: string;
  consumerDraft: string;
  editedCopy?: string | null;
  quote: string;
  quoteScore?: number;
  numbersOk?: boolean;
  findingClass?: string;
  risk?: string;
  doctorStatus: string;
  passage?: {
    passageId: string;
    page: number;
    section?: string;
    bbox?: BBox;
    pageWidth?: number;
    pageHeight?: number;
    text?: string;
  };
}

type Lookup = { state: "loading" } | { state: "ok"; claim: ClaimRecord } | { state: "missing"; reason: string };

interface Props {
  paperId: string;
  selection: Selection | null;
  /** Claims of this paper, keyed by claimId (from GET /api/papers/{id}/claims). */
  claims: Record<string, ClaimRecord>;
  onClose: () => void;
}

/** Click-to-source drawer: what a rendered block or reel scene says, and where in the paper it comes from. */
export function SourceDrawer({ paperId, selection, claims, onClose }: Props) {
  // Claims not in the paper list (e.g. added after load) are fetched by ID and must belong to this paper.
  const [fetched, setFetched] = useState<Record<string, Lookup>>({});

  useEffect(() => {
    if (!selection || selection.kind !== "claim") return;
    const missing = selection.claimIds.filter((cid) => !claims[cid] && !fetched[cid]);
    if (missing.length === 0) return;
    setFetched((prev) => ({ ...prev, ...Object.fromEntries(missing.map((cid) => [cid, { state: "loading" } as Lookup])) }));
    missing.forEach((cid) => {
      apiRequest<ClaimRecord>(`/api/claims/${encodeURIComponent(cid)}`, { silent: true })
        .then((c) => {
          const lookup: Lookup =
            c.paperId === paperId
              ? { state: "ok", claim: c }
              : { state: "missing", reason: `belongs to ${c.paperId}, not ${paperId}` };
          setFetched((prev) => ({ ...prev, [cid]: lookup }));
        })
        .catch((e) =>
          setFetched((prev) => ({ ...prev, [cid]: { state: "missing", reason: e?.message || "not found" } }))
        );
    });
  }, [selection, claims, paperId]); // eslint-disable-line react-hooks/exhaustive-deps

  const lookup = (cid: string): Lookup =>
    claims[cid] ? { state: "ok", claim: claims[cid] } : fetched[cid] ?? { state: "loading" };

  return (
    <div className="bg-softPaper border border-blush rounded-lg p-4 shadow-sm space-y-3" data-testid="source-drawer">
      <div className="flex items-center justify-between border-b border-blush pb-2">
        <h3 className="text-xs font-bold uppercase tracking-wider text-ink/70 flex items-center gap-1.5">
          <FileText className="w-3.5 h-3.5 text-rust" /> Source evidence
        </h3>
        {selection && (
          <button onClick={onClose} className="text-ink/50 hover:text-ink" aria-label="Close source drawer">
            <X className="w-4 h-4" />
          </button>
        )}
      </div>

      {!selection ? (
        <div className="text-center py-6 text-xs text-ink/50 space-y-1">
          <p>Click a headline, body text or reel scene</p>
          <p className="text-[11px] text-ink/40">to see the paper passage it comes from</p>
        </div>
      ) : (
        <div className="space-y-3" data-testid="drawer-content" data-kind={selection.kind}>
          <div className="flex items-center justify-between gap-2">
            <span className="text-xs font-mono font-medium text-ink/80">{selection.blockId}</span>
            <span
              className={`text-[10px] font-mono font-bold uppercase px-2 py-0.5 rounded border ${
                selection.kind === "claim"
                  ? "bg-rust/20 text-rust border-rust/40"
                  : selection.kind === "illustration"
                  ? "bg-clay/20 text-clay border-clay/40"
                  : "bg-maroon/20 text-maroon border-maroon/40"
              }`}
            >
              {selection.kind}
            </span>
          </div>

          {selection.text && (
            <p className="text-xs text-ink bg-cream border border-blush/60 rounded p-2 leading-snug">
              “{plainText(selection.text)}”
            </p>
          )}

          {selection.kind === "brand" && (
            <div className="bg-cream border border-blush rounded p-3 text-xs" data-testid="drawer-brand">
              <p className="font-semibold text-ink">Brand line.</p>
              <p className="text-[11px] text-ink/60 mt-1">Steer voice copy, not a study finding. It cites nothing.</p>
            </div>
          )}

          {selection.kind === "illustration" && (
            <div className="bg-cream border border-blush rounded p-3 text-xs" data-testid="drawer-illustration">
              <p className="font-semibold text-ink">Illustration, not a study finding.</p>
              <p className="text-[11px] text-ink/60 mt-1">An everyday moment for context. It states no fact from the paper.</p>
            </div>
          )}

          {selection.kind === "claim" &&
            (selection.claimIds.length === 0 ? (
              <p className="text-xs text-red-800 bg-red-50 border border-red-200 rounded p-2">
                This block is marked as a claim but has no claim IDs. It should be blocked by policy.
              </p>
            ) : (
              selection.claimIds.map((cid) => {
                const l = lookup(cid);
                if (l.state === "loading") {
                  return (
                    <div key={cid} className="text-xs text-ink/60">
                      Loading <span className="font-mono">{cid}</span>…
                    </div>
                  );
                }
                if (l.state === "missing") {
                  return (
                    <div key={cid} className="text-xs text-red-800 bg-red-50 border border-red-200 rounded p-2">
                      <span className="font-mono">{cid}</span>: {l.reason}
                    </div>
                  );
                }
                return <ClaimEvidence key={cid} paperId={paperId} claim={l.claim} />;
              })
            ))}
        </div>
      )}
    </div>
  );
}

function ClaimEvidence({ paperId, claim }: { paperId: string; claim: ClaimRecord }) {
  const reviewed = claim.doctorStatus === "APPROVED" || claim.doctorStatus === "EDITED";
  const approvedCopy = claim.editedCopy || claim.consumerDraft;
  const p = claim.passage;

  return (
    <div className="bg-cream border border-blush rounded-md p-3 space-y-2 text-xs shadow-sm" data-testid="claim-evidence">
      <div className="flex items-center justify-between">
        <span className="font-mono font-bold text-rust text-[11px]">{claim.claimId}</span>
        <div className="flex items-center gap-1.5">
          {claim.findingClass && (
            <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-softPaper border border-blush">
              {claim.findingClass}
            </span>
          )}
          {claim.risk && (
            <span
              className={`text-[10px] font-bold px-1.5 py-0.5 rounded ${
                claim.risk === "GREEN"
                  ? "bg-emerald-100 text-emerald-800"
                  : claim.risk === "AMBER"
                  ? "bg-amber-100 text-amber-800"
                  : "bg-red-100 text-red-800"
              }`}
            >
              {claim.risk}
            </span>
          )}
        </div>
      </div>

      <div>
        <div className="text-[10px] font-bold text-ink/60 uppercase">Claim</div>
        <p className="text-ink font-medium leading-tight mt-0.5">{claim.scientificClaim}</p>
      </div>

      <div>
        <div className="text-[10px] font-bold text-ink/60 uppercase">
          {reviewed ? `Approved copy (${claim.doctorStatus.toLowerCase()})` : `Consumer copy (${claim.doctorStatus.toLowerCase()}, not yet approved)`}
        </div>
        <p className="text-ink/90 leading-snug mt-0.5">{approvedCopy}</p>
      </div>

      <div>
        <div className="text-[10px] font-bold text-ink/60 uppercase">Exact quote from the paper</div>
        <p className="text-ink/80 italic bg-softPaper p-2 rounded border border-blush/60 mt-0.5 leading-snug" data-testid="claim-quote">
          “{claim.quote}”
        </p>
        <div className="flex items-center justify-between text-[10px] text-ink/50 mt-1">
          {claim.quoteScore != null && <span>Quote match: {claim.quoteScore}%</span>}
          {claim.numbersOk != null && <span>Numbers: {claim.numbersOk ? "✓ verified" : "✗ mismatch"}</span>}
        </div>
      </div>

      {p ? (
        <div className="pt-2 border-t border-blush/60 space-y-1.5">
          <div className="flex items-center justify-between text-[11px] gap-2">
            <span className="font-bold text-ink">
              Page {p.page}
              {p.section ? ` · ${p.section}` : ""}
            </span>
            <Link
              href={`/papers/${paperId}/claims?focus=${encodeURIComponent(claim.claimId)}`}
              className="text-rust hover:underline inline-flex items-center gap-1 text-[10px] whitespace-nowrap"
              data-testid="open-full-source"
            >
              Open full source <ExternalLink className="w-3 h-3" />
            </Link>
          </div>
          <div className="border border-blush rounded overflow-auto max-h-56 bg-white" data-scroll-container>
            <PageHighlight
              paperId={paperId}
              page={p.page}
              bbox={p.bbox}
              pageWidth={p.pageWidth}
              pageHeight={p.pageHeight}
              className="w-full"
              scrollToHighlight
            />
          </div>
        </div>
      ) : (
        <p className="text-[11px] text-ink/60">No passage linked to this claim.</p>
      )}
    </div>
  );
}
