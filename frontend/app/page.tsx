"use client";

import React, { useEffect, useState, useRef } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { apiRequest, showToast, API_BASE } from "@/lib/api";
import { useReviewer } from "@/components/ReviewerContext";

interface PaperSummary {
  id: string;
  title?: string;
  authors?: string;
  year?: number;
  journal?: string;
  status: string;
  pdfPath?: string;
}

export default function HomePage() {
  const router = useRouter();
  const { reviewerName } = useReviewer();
  const [papers, setPapers] = useState<PaperSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [theme, setTheme] = useState("");
  const [urlInput, setUrlInput] = useState("");
  const [dragActive, setDragActive] = useState(false);
  const startTimeRef = useRef<number>(Date.now());

  useEffect(() => {
    fetchPapers();
  }, []);

  const fetchPapers = async () => {
    try {
      const data = await apiRequest<PaperSummary[]>("/api/papers");
      setPapers(data);
    } catch (e) {
      console.error(e);
    } finally {
      setLoading(false);
    }
  };

  const handleUploadFile = async (file: File) => {
    setUploading(true);
    const elapsedSeconds = Math.max(1, Math.round((Date.now() - startTimeRef.current) / 1000));
    try {
      const formData = new FormData();
      formData.append("file", file);
      if (theme.trim()) {
        formData.append("theme", theme.trim());
      }

      const res = await fetch(`${API_BASE}/api/papers/upload`, {
        method: "POST",
        body: formData,
      });

      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || `Upload failed: ${res.statusText}`);
      }

      const data = await res.json();
      showToast(`Paper ${data.paperId || data.id} uploaded successfully!`, "success");

      // Log UPLOAD human event
      const paperId = data.paperId || data.id;
      if (paperId) {
        await apiRequest(`/api/papers/${paperId}/human-events`, {
          method: "POST",
          body: JSON.stringify({
            kind: "UPLOAD",
            reviewer: reviewerName,
            seconds: elapsedSeconds,
          }),
        }).catch(() => {});
      }

      router.push(`/papers/${paperId}`);
    } catch (err: any) {
      showToast(err.message || "Failed to upload file", "error");
    } finally {
      setUploading(false);
    }
  };

  const handleUrlSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!urlInput.trim()) return;
    setUploading(true);
    const elapsedSeconds = Math.max(1, Math.round((Date.now() - startTimeRef.current) / 1000));
    try {
      const data = await apiRequest<{ paperId: string }>("/api/papers/upload-url", {
        method: "POST",
        body: JSON.stringify({ url: urlInput.trim(), theme: theme.trim() || undefined }),
      });
      showToast(`Paper ${data.paperId} downloaded and registered!`, "success");

      await apiRequest(`/api/papers/${data.paperId}/human-events`, {
        method: "POST",
        body: JSON.stringify({
          kind: "UPLOAD",
          reviewer: reviewerName,
          seconds: elapsedSeconds,
        }),
      }).catch(() => {});

      router.push(`/papers/${data.paperId}`);
    } catch (err: any) {
      showToast(err.message || "Failed to process paper URL", "error");
    } finally {
      setUploading(false);
    }
  };

  const handleSamplePaper = () => {
    // If P-0001 exists in database, go directly to it
    const existingP1 = papers.find((p) => p.id === "P-0001");
    if (existingP1) {
      router.push("/papers/P-0001");
    } else {
      setUrlInput("https://doi.org/10.1016/j.mcna.2017.08.012");
      setTheme("Weight regain biology & metabolic adaptation");
    }
  };

  const getStatusBadge = (status: string) => {
    switch (status) {
      case "EXPORTED":
        return "bg-emerald-900/10 text-emerald-800 border-emerald-300";
      case "AWAITING_CLAIM_REVIEW":
        return "bg-amber-900/10 text-amber-800 border-amber-300 font-semibold";
      case "AWAITING_ASSET_REVIEW":
        return "bg-blue-900/10 text-blue-800 border-blue-300 font-semibold";
      case "PARSING":
      case "EXTRACTING":
      case "VERIFYING":
      case "WRITING":
      case "RENDERING":
        return "bg-purple-900/10 text-purple-800 border-purple-300 animate-pulse";
      case "FAILED":
        return "bg-red-900/10 text-red-800 border-red-300";
      default:
        return "bg-blush/40 text-ink border-blush";
    }
  };

  return (
    <div className="max-w-6xl mx-auto px-4 py-8">
      {/* Hero Header */}
      <div className="mb-10 text-center max-w-2xl mx-auto">
        <h1 className="font-serif text-4xl sm:text-5xl font-normal text-ink mb-3 tracking-tight">
          Clinical metabolic evidence, <span className="italic text-rust">verified</span>.
        </h1>
        <p className="text-clay text-sm sm:text-base leading-relaxed">
          Upload clinical research papers to extract and audit claims with doctor oversight,
          and automatically generate multi-format social assets with mathematical precision.
        </p>
      </div>

      {/* Upload Box */}
      <div className="bg-softPaper border border-blush rounded-2xl p-6 sm:p-8 mb-12 shadow-sm">
        <div className="flex flex-col md:flex-row gap-6 items-start">
          {/* Drag & Drop Area */}
          <div
            onDragOver={(e) => {
              e.preventDefault();
              setDragActive(true);
            }}
            onDragLeave={() => setDragActive(false)}
            onDrop={(e) => {
              e.preventDefault();
              setDragActive(false);
              if (e.dataTransfer.files?.[0]) {
                handleUploadFile(e.dataTransfer.files[0]);
              }
            }}
            className={`flex-1 border-2 border-dashed rounded-xl p-8 text-center transition-all cursor-pointer w-full ${
              dragActive ? "border-rust bg-cream" : "border-blush hover:border-clay bg-cream/50"
            }`}
            onClick={() => {
              const fileInput = document.getElementById("file-upload") as HTMLInputElement;
              fileInput?.click();
            }}
          >
            <input
              id="file-upload"
              type="file"
              accept=".pdf,application/pdf"
              className="hidden"
              onChange={(e) => {
                if (e.target.files?.[0]) {
                  handleUploadFile(e.target.files[0]);
                }
              }}
            />
            <div className="flex flex-col items-center">
              <span className="text-3xl mb-2 text-rust">📄</span>
              <p className="text-sm font-medium text-ink mb-1">
                {uploading ? "Processing PDF..." : "Drag & drop clinical PDF paper here"}
              </p>
              <p className="text-xs text-clay">or click to browse your computer</p>
            </div>
          </div>

          {/* Form options & URL input */}
          <div className="w-full md:w-80 flex flex-col gap-4">
            <div>
              <label className="block text-xs uppercase tracking-wider text-clay font-medium mb-1">
                Optional Editorial Theme
              </label>
              <input
                type="text"
                placeholder="e.g. Weight regain biology"
                value={theme}
                onChange={(e) => setTheme(e.target.value)}
                className="w-full px-3 py-2 text-xs border border-blush rounded-lg bg-cream text-ink focus:outline-none focus:border-rust"
              />
            </div>

            <div>
              <label className="block text-xs uppercase tracking-wider text-clay font-medium mb-1">
                Or paste PDF URL
              </label>
              <form onSubmit={handleUrlSubmit} className="flex gap-2">
                <input
                  type="url"
                  placeholder="https://..."
                  value={urlInput}
                  onChange={(e) => setUrlInput(e.target.value)}
                  className="flex-1 px-3 py-2 text-xs border border-blush rounded-lg bg-cream text-ink focus:outline-none focus:border-rust"
                />
                <button
                  type="submit"
                  disabled={uploading || !urlInput.trim()}
                  className="px-3 py-2 text-xs bg-rust text-cream font-medium rounded-lg hover:bg-[#A84222] disabled:opacity-50 transition-colors"
                >
                  Fetch
                </button>
              </form>
            </div>

            <div className="pt-2 border-t border-blush">
              <button
                type="button"
                onClick={handleSamplePaper}
                className="w-full py-2 px-3 text-xs text-rust font-medium bg-cream border border-rust/30 rounded-lg hover:bg-blush/30 transition-colors text-center"
              >
                Sample: Hall & Kahan (2018)
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Papers List */}
      <div>
        <div className="flex justify-between items-center mb-4">
          <h2 className="font-serif text-2xl text-ink font-normal">Active Papers in SteerProof</h2>
          <span className="text-xs text-clay">{papers.length} paper{papers.length !== 1 ? "s" : ""}</span>
        </div>

        {loading ? (
          <div className="p-8 text-center text-clay text-sm">Loading papers...</div>
        ) : papers.length === 0 ? (
          <div className="bg-softPaper border border-blush rounded-xl p-8 text-center text-clay text-sm">
            No papers registered yet. Upload your first clinical study above.
          </div>
        ) : (
          <div className="grid gap-3">
            {papers.map((p) => (
              <Link
                key={p.id}
                href={`/papers/${p.id}`}
                className="bg-softPaper hover:bg-cream border border-blush hover:border-clay rounded-xl p-4 sm:p-5 transition-all flex flex-col sm:flex-row justify-between sm:items-center gap-3 group"
              >
                <div className="flex-1">
                  <div className="flex items-center gap-2 mb-1">
                    <span className="text-xs font-mono text-clay">{p.id}</span>
                    <span className={`text-[11px] px-2 py-0.5 rounded-full border ${getStatusBadge(p.status)}`}>
                      {p.status.replace(/_/g, " ")}
                    </span>
                  </div>
                  <h3 className="font-sans font-medium text-ink group-hover:text-rust transition-colors text-base line-clamp-1">
                    {p.title || "Clinical Research Paper"}
                  </h3>
                  <p className="text-xs text-clay mt-1">
                    {p.authors || "Authors"} {p.year ? `(${p.year})` : ""} {p.journal ? `· ${p.journal}` : ""}
                  </p>
                </div>

                <div className="flex items-center gap-3">
                  <span className="text-xs text-rust font-medium flex items-center gap-1 group-hover:translate-x-0.5 transition-transform">
                    View Pipeline →
                  </span>
                </div>
              </Link>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
