import { getToken } from "./auth";

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
}

export async function api<T>(path: string, options: CallOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (options.idempotencyKey) headers["Idempotency-Key"] = options.idempotencyKey;
  if (!options.anonymous) {
    const token = await getToken();
    if (token) headers.Authorization = `Bearer ${token}`;
  }
  const response = await fetch(path, {
    method: options.method ?? (options.body === undefined ? "GET" : "POST"),
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
  const text = await response.text();
  let data: { code?: string; detail?: string; message?: string } | null = null;
  if (text) {
    try {
      data = JSON.parse(text) as { code?: string; detail?: string; message?: string };
    } catch {
      throw new ApiError(response.status, "invalid_response", response.statusText || `HTTP ${response.status}`);
    }
  }
  if (!response.ok) {
    if (response.status === 401 && !options.anonymous) {
      try {
        sessionStorage.removeItem("elepay.nativeToken");
        sessionStorage.removeItem("elepay.authUser");
        sessionStorage.removeItem("elepay.devToken");
      } catch {
        // ignore storage errors
      }
    }
    throw new ApiError(
      response.status,
      data?.code ?? "error",
      data?.detail ?? data?.message ?? (response.statusText || `HTTP ${response.status}`),
    );
  }
  return data as T;
}
