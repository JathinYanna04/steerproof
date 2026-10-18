"use client";

import React, { useEffect, useState } from "react";
import { subscribeToasts } from "@/lib/api";

interface ToastItem {
  id: number;
  message: string;
  type: "error" | "success" | "info";
}

export const ToastContainer: React.FC = () => {
  const [toasts, setToasts] = useState<ToastItem[]>([]);

  useEffect(() => {
    return subscribeToasts((message, type) => {
      const id = Date.now() + Math.random();
      setToasts((prev) => [...prev, { id, message, type }]);
      setTimeout(() => {
        setToasts((prev) => prev.filter((t) => t.id !== id));
      }, 5000);
    });
  }, []);

  if (toasts.length === 0) return null;

  return (
    <div className="fixed bottom-5 right-5 z-50 flex flex-col gap-2 max-w-md w-full pointer-events-none">
      {toasts.map((t) => (
        <div
          key={t.id}
          data-testid={`toast-${t.type}`}
          className={`pointer-events-auto p-4 rounded-lg shadow-md border text-sm transition-all duration-300 ${
            t.type === "error"
              ? "bg-maroon text-cream border-maroon"
              : t.type === "success"
              ? "bg-emerald-900 text-cream border-emerald-800"
              : "bg-softPaper text-ink border-blush"
          }`}
        >
          <div className="flex justify-between items-start gap-2">
            <span className="font-sans leading-relaxed break-words">{t.message}</span>
            <button
              onClick={() => setToasts((prev) => prev.filter((item) => item.id !== t.id))}
              className="text-xs opacity-75 hover:opacity-100 font-bold ml-2"
            >
              ✕
            </button>
          </div>
        </div>
      ))}
    </div>
  );
};
