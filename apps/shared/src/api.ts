import { canonicalV2, hmacSha256Hex } from "./hmac";
import type { DeviceCredential, WaiterDeviceCredential } from "./types";

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
    this.name = "ApiError";
  }
}

/** Generates a UUIDv4 idempotency / nonce key compatible with React Native Hermes. */
export function newKey(): string {
  if (typeof globalThis.crypto !== "undefined" && typeof globalThis.crypto.randomUUID === "function") {
    return globalThis.crypto.randomUUID();
  }
  const hex = "0123456789abcdef";
  let out = "";
  for (let i = 0; i < 36; i++) {
    if (i === 8 || i === 13 || i === 18 || i === 23) {
      out += "-";
    } else if (i === 14) {
      out += "4";
    } else if (i === 19) {
      out += hex[(Math.floor(Math.random() * 4) + 8)];
    } else {
      out += hex[Math.floor(Math.random() * 16)];
    }
  }
  return out;
}

const DEFAULT_TIMEOUT_MS = 20_000;

export interface CallOptions {
  method?: string;
  body?: unknown;
  idempotencyKey?: string;
  anonymous?: boolean;
  timeoutMs?: number;
}

interface ErrorBody {
  code?: string;
  detail?: string;
  message?: string;
  errors?: unknown[];
}

function formatErrorMessage(data: ErrorBody | null, response: Response): string {
  const base = data?.detail || data?.message || response.statusText || `HTTP ${response.status}`;
  const errs = Array.isArray(data?.errors)
    ? data.errors.filter((e): e is string => typeof e === "string" && e.trim().length > 0)
    : [];
  return errs.length > 0 ? `${base}: ${errs.join("; ")}` : base;
}

/**
 * Splits the request path (relative to the API base URL) into the signed path and raw query.
 * Always signs the exact path sent, never a parsed absolute URL: the server signs
 * servletPath + pathInfo (excluding any context/base prefix), and URL.pathname is not
 * implemented on React Native, which made web and device signatures diverge.
 */
export function signingPathAndQuery(path: string): { pathname: string; query: string | null } {
  const normalized = path.startsWith("/") ? path : `/${path}`;
  const qIndex = normalized.indexOf("?");
  const pathname = qIndex >= 0 ? normalized.slice(0, qIndex) : normalized;
  const query = qIndex >= 0 ? normalized.slice(qIndex + 1) : null;
  return { pathname, query: query || null };
}

/** Allocates one idempotency key per logical attempt (signature) so retries reuse it. */
export function createAttemptKeys(): { keyFor: (sig: string) => string; reset: () => void } {
  let current: { sig: string; key: string } | null = null;
  return {
    keyFor(sig: string) {
      if (!current || current.sig !== sig) current = { sig, key: newKey() };
      return current.key;
    },
    reset() {
      current = null;
    },
  };
}

async function fetchWithTimeout(url: string, init: RequestInit, timeoutMs: number): Promise<Response> {
  if (typeof AbortController === "undefined" || timeoutMs <= 0) {
    return fetch(url, init);
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...init, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

export function normalizeBaseUrl(url: string): string {
  return url.trim().replace(/\/+$/, "");
}

export class ElepayClient {
  private baseUrl: string;
  private token: string | null = null;
  private clockOffsetMs = 0;

  constructor(baseUrl: string, token: string | null = null) {
    this.baseUrl = normalizeBaseUrl(baseUrl);
    this.token = token;
  }

  getBaseUrl(): string {
    return this.baseUrl;
  }

  setBaseUrl(url: string): void {
    this.baseUrl = normalizeBaseUrl(url);
  }

  getToken(): string | null {
    return this.token;
  }

  setToken(token: string | null): void {
    this.token = token;
  }

  learnServerTime(serverTime: string): void {
    const t = Date.parse(serverTime);
    if (!Number.isNaN(t)) {
      this.clockOffsetMs = t - Date.now();
    }
  }

  getServerNow(): number {
    return Date.now() + this.clockOffsetMs;
  }

  async call<T>(path: string, options: CallOptions = {}): Promise<T> {
    const headers: Record<string, string> = { Accept: "application/json" };
    if (options.body !== undefined) headers["Content-Type"] = "application/json";
    if (options.idempotencyKey) headers["Idempotency-Key"] = options.idempotencyKey;
    if (!options.anonymous && this.token) {
      headers.Authorization = `Bearer ${this.token}`;
    }

    const normalizedPath = path.startsWith("/") ? path : `/${path}`;
    const url = `${this.baseUrl}${normalizedPath}`;
    const response = await fetchWithTimeout(
      url,
      {
        method: (options.method ?? (options.body === undefined ? "GET" : "POST")).toUpperCase(),
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
      },
      options.timeoutMs ?? DEFAULT_TIMEOUT_MS,
    );

    const text = await response.text();
    let data: ErrorBody | null = null;
    if (text) {
      try {
        data = JSON.parse(text) as ErrorBody;
      } catch {
        throw new ApiError(response.status, "invalid_response", response.statusText || "Invalid server response");
      }
    }
    if (!response.ok) {
      throw new ApiError(response.status, data?.code ?? "error", formatErrorMessage(data, response));
    }
    return data as T;
  }

  /** Development / sandbox profile sign-in using /dev/token. */
  async signInDev(subject: string, roles: string[] = []): Promise<string> {
    const res = await this.call<{ accessToken: string }>("/dev/token", {
      method: "POST",
      anonymous: true,
      body: { subject: subject.trim(), roles },
    });
    this.token = res.accessToken;
    return res.accessToken;
  }

  /** Pair a dedicated KDS tablet using the manager's 6-digit merchant code + 6-digit pairing code. */
  async pairKitchenDevice(merchantCode: string, code: string, deviceName: string): Promise<DeviceCredential> {
    return this.call<DeviceCredential>("/v1/public/kitchen/pair", {
      method: "POST",
      anonymous: true,
      body: { merchantCode: merchantCode.trim(), code: code.replace(/\s/g, ""), deviceName: deviceName.trim() || "Kitchen Tablet" },
    });
  }

  /** Pairs a waiter's phone with the one-time code a manager created for that waiter. */
  async pairWaiterDevice(merchantCode: string, code: string, deviceName: string): Promise<WaiterDeviceCredential> {
    return this.call<WaiterDeviceCredential>("/v1/public/waiter/pair", {
      method: "POST",
      anonymous: true,
      body: { merchantCode: merchantCode.trim(), code: code.replace(/\s/g, ""), deviceName: deviceName.trim() || "Waiter phone" },
    });
  }

  /** Execute an API request as a paired waiter phone, signed exactly like a kitchen device. */
  async callAsWaiterDevice<T>(device: WaiterDeviceCredential, path: string, options: CallOptions = {}): Promise<T> {
    return this.callAsKitchenDevice<T>(device, path, options);
  }

  /** Execute an API request authenticated as a paired KDS device with HMAC-SHA256 v2 request signing. */
  async callAsKitchenDevice<T>(device: Pick<DeviceCredential, "apiKey" | "signingSecret">, path: string, options: CallOptions = {}): Promise<T> {
    const method = (options.method ?? (options.body === undefined ? "GET" : "POST")).toUpperCase();
    const body = options.body === undefined ? "" : JSON.stringify(options.body);
    const headers: Record<string, string> = {
      Accept: "application/json",
      "X-Elepay-Api-Key": device.apiKey,
    };
    if (options.body !== undefined) {
      headers["Content-Type"] = "application/json";
    }
    if (options.idempotencyKey) {
      headers["Idempotency-Key"] = options.idempotencyKey;
    }

    const normalizedPath = path.startsWith("/") ? path : `/${path}`;
    const url = `${this.baseUrl}${normalizedPath}`;
    if (method !== "GET") {
      const { pathname, query } = signingPathAndQuery(normalizedPath);
      const timestamp = String(Math.floor((Date.now() + this.clockOffsetMs) / 1000));
      const nonce = newKey();
      headers["X-Elepay-Timestamp"] = timestamp;
      headers["X-Elepay-Nonce"] = nonce;
      headers["X-Elepay-Signature-Version"] = "2";
      headers["X-Elepay-Signature"] = hmacSha256Hex(
        device.signingSecret,
        canonicalV2([timestamp, method, pathname, query, nonce, options.idempotencyKey ?? null, body]),
      );
    }

    const response = await fetchWithTimeout(
      url,
      {
        method,
        headers,
        body: options.body === undefined ? undefined : body,
      },
      options.timeoutMs ?? DEFAULT_TIMEOUT_MS,
    );
    const text = await response.text();
    let data: ErrorBody | null = null;
    if (text) {
      try {
        data = JSON.parse(text) as ErrorBody;
      } catch {
        throw new ApiError(response.status, "invalid_response", response.statusText || "Invalid server response");
      }
    }
    if (!response.ok) {
      throw new ApiError(response.status, data?.code ?? "error", formatErrorMessage(data, response));
    }
    return data as T;
  }
}
