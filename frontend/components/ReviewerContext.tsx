"use client";

import React, { createContext, useContext, useEffect, useState } from "react";

interface ReviewerContextType {
  reviewer: string;
  reviewerName: string;
  setReviewerName: (name: string) => void;
}

const DEFAULT_REVIEWER = "Jathin Reddy Yanna (non-clinician reviewer)";
const STORAGE_KEY = "steerproof_reviewer_name";

const ReviewerContext = createContext<ReviewerContextType>({
  reviewer: DEFAULT_REVIEWER,
  reviewerName: DEFAULT_REVIEWER,
  setReviewerName: () => {},
});

export const ReviewerProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [reviewerName, setReviewerNameState] = useState<string>(DEFAULT_REVIEWER);
  const [isPromptOpen, setIsPromptOpen] = useState(false);
  const [tempName, setTempName] = useState(DEFAULT_REVIEWER);

  useEffect(() => {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved && saved.trim()) {
      setReviewerNameState(saved.trim());
    } else {
      setIsPromptOpen(true);
    }
  }, []);

  const setReviewerName = (name: string) => {
    const clean = name.trim() || DEFAULT_REVIEWER;
    setReviewerNameState(clean);
    localStorage.setItem(STORAGE_KEY, clean);
  };

  const handleSave = () => {
    setReviewerName(tempName);
    setIsPromptOpen(false);
  };

  return (
    <ReviewerContext.Provider value={{ reviewer: reviewerName, reviewerName, setReviewerName }}>
      {children}
      {isPromptOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4">
          <div className="bg-softPaper border border-blush rounded-xl p-6 max-w-md w-full shadow-lg">
            <h2 className="font-serif text-2xl text-ink font-normal mb-2">Welcome to SteerProof</h2>
            <p className="text-sm text-clay mb-4">
              Please enter your name as reviewer. This will be recorded on all clinical reviews, audit trails, and export packages.
            </p>
            <input
              type="text"
              value={tempName}
              onChange={(e) => setTempName(e.target.value)}
              className="w-full px-3 py-2 border border-blush rounded-md bg-cream text-ink text-sm focus:outline-none focus:border-rust mb-4"
              placeholder="Reviewer Name"
            />
            <div className="flex justify-end gap-2">
              <button
                onClick={handleSave}
                className="px-4 py-2 bg-rust text-cream text-sm font-medium rounded-md hover:bg-[#A84222] transition-colors"
              >
                Set Reviewer Name
              </button>
            </div>
          </div>
        </div>
      )}
    </ReviewerContext.Provider>
  );
};

export const useReviewer = () => useContext(ReviewerContext);
