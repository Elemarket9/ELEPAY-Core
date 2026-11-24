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
  if (!path.startsWith("/") || path.startsWith("//") || /[\\\u0000-\u001f\u007f]/.test(path)) {
    throw new ApiError(400, "invalid_api_path", "Invalid API request path");
  }
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
    cache: "no-store",
    redirect: "error",
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
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
    throw new ApiError(response.status, data?.code ?? "error", data?.detail ?? data?.message ?? response.statusText);
  }
  return data as T;
}
