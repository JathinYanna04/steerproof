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

export async function apiRequest<T = any>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const url = path.startsWith("http") ? path : `${API_BASE}${path}`;
  const headers = new Headers(options.headers || {});
  if (!headers.has("Content-Type") && options.body && typeof options.body === "string") {
    headers.set("Content-Type", "application/json");
  }

  try {
    const res = await fetch(url, { ...options, headers });
    if (!res.ok) {
      let errorMsg = `HTTP ${res.status}: ${res.statusText}`;
      let errJson: any = null;
      try {
        errJson = await res.json();
        if (errJson.message) {
          errorMsg = errJson.message;
        } else if (errJson.error) {
          errorMsg = errJson.error;
        }
        if (errJson.blockedBy) {
          const list = errJson.blockedBy.map((b: any) => `${b.assetId || b.type}: ${b.reason || b.status}`).join(", ");
          errorMsg = `${errorMsg}: ${list}`;
        }
      } catch (ignored) {}
      
      showToast(errorMsg, "error");
      const errObj: any = new Error(errorMsg);
      errObj.status = res.status;
      errObj.details = errJson?.details;
      errObj.blockedBy = errJson?.blockedBy;
      errObj.errJson = errJson;
      throw errObj;
    }

    if (res.status === 204) {
      return {} as T;
    }
    return await res.json();
  } catch (err: any) {
    if (!err.status) {
      showToast(err.message || "Network request failed", "error");
    }
    throw err;
  }
}
