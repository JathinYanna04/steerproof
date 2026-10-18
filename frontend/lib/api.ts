export const API_BASE = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";

export interface ApiError {
  error: string;
  message: string;
  details?: any;
}

type ToastListener = (msg: string, type: "error" | "success" | "info") => void;
const listeners: ToastListener[] = [];

export function subscribeToasts(listener: ToastListener) {
  listeners.push(listener);
  return () => {
    const idx = listeners.indexOf(listener);
    if (idx !== -1) listeners.splice(idx, 1);
  };
}

export function showToast(message: string, type: "error" | "success" | "info" = "info") {
  listeners.forEach((l) => l(message, type));
}

export interface RequestOptions extends RequestInit {
  /** Don't toast on failure (background polling, optional endpoints). The error is still thrown. */
  silent?: boolean;
}

/** Builds the message shown to the user from the server's error body, keeping the server's own words. */
function serverErrorMessage(res: Response, errJson: any): string {
  if (!errJson) return `HTTP ${res.status}: ${res.statusText}`;
  const parts: string[] = [];
  if (errJson.error) parts.push(String(errJson.error));
  if (errJson.message && errJson.message !== errJson.error) parts.push(String(errJson.message));
  let msg = parts.join(": ") || `HTTP ${res.status}: ${res.statusText}`;
  const blockedBy = errJson.blockedBy || errJson.details?.blockedBy;
  if (Array.isArray(blockedBy) && blockedBy.length > 0) {
    const list = blockedBy
      .map((b: any) => `${b.assetId || b.claimId || b.type}: ${b.reason || b.status}`)
      .join(", ");
    msg = `${msg} (${list})`;
  }
  return msg;
}

export async function apiRequest<T = any>(path: string, options: RequestOptions = {}): Promise<T> {
  const { silent, ...init } = options;
  const url = path.startsWith("http") ? path : `${API_BASE}${path}`;
  const headers = new Headers(init.headers || {});
  if (!headers.has("Content-Type") && init.body && typeof init.body === "string") {
    headers.set("Content-Type", "application/json");
  }

  let res: Response;
  try {
    res = await fetch(url, { ...init, headers });
  } catch (err: any) {
    if (!silent) showToast(err?.message || "Network request failed", "error");
    throw err;
  }

  if (!res.ok) {
    let errJson: any = null;
    try {
      errJson = await res.json();
    } catch {
      errJson = null;
    }
    const errorMsg = serverErrorMessage(res, errJson);
    if (!silent) showToast(errorMsg, "error");
    const errObj: any = new Error(errorMsg);
    errObj.status = res.status;
    errObj.details = errJson?.details;
    errObj.blockedBy = errJson?.blockedBy || errJson?.details?.blockedBy;
    errObj.errJson = errJson;
    throw errObj;
  }

  if (res.status === 204) {
    return {} as T;
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : {}) as T;
}

/** Public URL for a file stored under STORAGE_DIR (paths are stored relative to it). */
export function fileUrl(relPath: string): string {
  return `${API_BASE}/files/${relPath.replace(/^\/+/, "")}`;
}

export function formatSeconds(sec: number | null | undefined): string {
  if (sec == null || Number.isNaN(sec)) return "–";
  if (sec < 10) return `${(Math.round(sec * 10) / 10).toString()}s`;
  const s = Math.round(sec);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = s % 60;
  if (h > 0) return `${h}h ${m}m`;
  return m > 0 ? `${m}m ${r}s` : `${r}s`;
}
