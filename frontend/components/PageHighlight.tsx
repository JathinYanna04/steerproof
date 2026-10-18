"use client";

import React from "react";
import { API_BASE } from "@/lib/api";

export interface BBox {
  l: number;
  t: number;
  r: number;
  b: number;
}

interface Props {
  paperId: string;
  page: number;
  bbox?: BBox | null;
  /** PDF page size in points, from the Docling passage. Needed to place the box. */
  pageWidth?: number | null;
  pageHeight?: number | null;
  className?: string;
  imgClassName?: string;
  /** Scroll the highlight into view inside a clipped container (drawer thumbnail). */
  scrollToHighlight?: boolean;
}

/**
 * A rendered PDF page with the passage bounding box drawn over it. Coordinates come from Docling
 * (PDF points, top-left origin) and are placed as percentages, so the box tracks the image at any size.
 */
export function PageHighlight({
  paperId,
  page,
  bbox,
  pageWidth,
  pageHeight,
  className = "",
  imgClassName = "",
  scrollToHighlight = false,
}: Props) {
  const boxRef = React.useRef<HTMLDivElement>(null);
  const [loaded, setLoaded] = React.useState(false);

  const hasBox = !!bbox && !!pageWidth && !!pageHeight;
  const box = hasBox
    ? {
        left: `${(bbox!.l / pageWidth!) * 100}%`,
        top: `${(Math.min(bbox!.t, bbox!.b) / pageHeight!) * 100}%`,
        width: `${((bbox!.r - bbox!.l) / pageWidth!) * 100}%`,
        height: `${(Math.abs(bbox!.b - bbox!.t) / pageHeight!) * 100}%`,
      }
    : null;

  React.useEffect(() => {
    // Scroll only the clipping container, never the page
    const el = boxRef.current;
    const container = el?.closest("[data-scroll-container]") as HTMLElement | null;
    if (scrollToHighlight && loaded && el && container) {
      container.scrollTop = Math.max(0, el.offsetTop - container.clientHeight / 2 + el.offsetHeight / 2);
    }
  }, [scrollToHighlight, loaded, page, bbox?.t]);

  return (
    <div className={`relative inline-block bg-white ${className}`} data-testid="page-highlight">
      <img
        src={`${API_BASE}/files/pages/${paperId}/${page}.png`}
        alt={`Page ${page}`}
        className={`block w-full h-auto ${imgClassName}`}
        onLoad={() => setLoaded(true)}
      />
      {box && (
        <div
          ref={boxRef}
          data-testid="bbox-highlight"
          style={{ position: "absolute", ...box }}
          className="bg-rust/25 border-2 border-rust rounded-sm pointer-events-none"
        />
      )}
    </div>
  );
}
