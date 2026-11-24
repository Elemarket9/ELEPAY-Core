import { getToken, notifySessionExpired } from "./auth";

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

export function newKey(): string {
  return crypto.randomUUID();
}

export interface CallOptions {
  method?: string;
  body?: unknown;
  idempotencyKey?: string;
  anonymous?: boolean;
  signal?: AbortSignal;
}

export function safeApiPath(path: string): boolean {
  if (!path.startsWith("/") || path.startsWith("//") || /[\\\u0000-\u0020\u007f#]/.test(path)) return false;
  const pathname = path.split("?")[0];
  if (/%(?:2f|5c|2e|25|00|0d|0a)/i.test(pathname) || pathname.split("/").some(p => p==="." || p==="..")) return false;
  try { const u = new URL(path, "https://elepay.invalid"); return u.origin === "https://elepay.invalid" && u.pathname === pathname; } catch { return false; }
}

export async function api<T>(path: string, options: CallOptions = {}): Promise<T> {
  if (!safeApiPath(path)) throw new ApiError(400, "invalid_api_path", "Invalid API request path");
  const headers: Record<string, string> = { Accept: "application/json" };
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (options.idempotencyKey) headers["Idempotency-Key"] = options.idempotencyKey;
  if (!options.anonymous) {
    const token = await getToken();
    if (!token) { notifySessionExpired(); throw new ApiError(401,"session_required","Sign in again to continue."); }
    headers.Authorization = `Bearer ${token}`;
  }
  const response = await fetch(path, {
    method: options.method ?? (options.body === undefined ? "GET" : "POST"),
    headers,
    signal: options.signal ?? AbortSignal.timeout(30000),
    credentials: "omit",
    cache: "no-store",
    redirect: "error",
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
  if (response.status === 401 && !options.anonymous) notifySessionExpired();
  const text = await response.text();
  let data: { code?: string; detail?: string; message?: string } | null = null;
  if (text) {
    try {
      data = JSON.parse(text) as { code?: string; detail?: string; message?: string };
    } catch {
      throw new ApiError(response.status, "invalid_response", response.statusText || "Invalid response");
    }
  }
  if (!response.ok) {
    throw new ApiError(response.status, typeof data?.code==="string"?data.code:"error", typeof data?.detail==="string"?data.detail:typeof data?.message==="string"?data.message:response.statusText);
  }
  return data as T;
}
