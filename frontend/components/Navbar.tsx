"use client";

import React, { useState } from "react";
import Link from "next/link";
import { useReviewer } from "./ReviewerContext";

export const Navbar: React.FC = () => {
  const { reviewerName, setReviewerName } = useReviewer();
  const [isEditing, setIsEditing] = useState(false);
  const [editVal, setEditVal] = useState(reviewerName);

  const handleSave = () => {
    setReviewerName(editVal);
    setIsEditing(false);
  };

  return (
    <header className="border-b border-blush bg-softPaper/70 backdrop-blur-sm sticky top-0 z-40">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
        <div className="flex items-center gap-6">
          <Link href="/" className="font-serif text-3xl font-normal text-rust tracking-tight hover:opacity-90">
            steer
          </Link>
          <span className="text-xs uppercase tracking-widest px-2.5 py-0.5 rounded-full bg-cream border border-blush text-clay font-medium">
            SteerProof Studio
          </span>
          <nav className="hidden md:flex items-center gap-4 text-sm text-clay font-sans">
            <Link href="/" className="hover:text-ink transition-colors">Papers</Link>
          </nav>
        </div>

        <div className="flex items-center gap-3">
          {isEditing ? (
            <div className="flex items-center gap-2">
              <input
                type="text"
                value={editVal}
                onChange={(e) => setEditVal(e.target.value)}
                className="px-2 py-1 text-xs border border-blush rounded bg-cream text-ink focus:outline-none focus:border-rust"
              />
              <button
                onClick={handleSave}
                className="px-2.5 py-1 text-xs bg-rust text-cream rounded font-medium hover:bg-[#A84222]"
              >
                Save
              </button>
            </div>
          ) : (
            <div className="flex items-center gap-2 text-xs text-clay bg-cream px-3 py-1.5 rounded-full border border-blush">
              <span className="w-2 h-2 rounded-full bg-rust" />
              <span className="text-ink font-medium max-w-[200px] sm:max-w-xs truncate">{reviewerName}</span>
              <button
                onClick={() => {
                  setEditVal(reviewerName);
                  setIsEditing(true);
                }}
                className="text-clay hover:text-rust underline ml-1"
                title="Change reviewer name"
              >
                edit
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  );
};
