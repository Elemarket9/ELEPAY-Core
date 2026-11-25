import { canonicalV2, hmacSha256Hex } from "./hmac";
import type { DeviceCredential } from "./types";

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

export interface CallOptions {
  method?: string;
  body?: unknown;
  idempotencyKey?: string;
  anonymous?: boolean;
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

  async call<T>(path: string, options: CallOptions = {}): Promise<T> {
    const headers: Record<string, string> = { Accept: "application/json" };
    if (options.body !== undefined) headers["Content-Type"] = "application/json";
    if (options.idempotencyKey) headers["Idempotency-Key"] = options.idempotencyKey;
    if (!options.anonymous && this.token) {
      headers.Authorization = `Bearer ${this.token}`;
    }

    const url = `${this.baseUrl}${path.startsWith("/") ? path : `/${path}`}`;
    const response = await fetch(url, {
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
        throw new ApiError(response.status, "invalid_response", response.statusText || "Invalid server response");
      }
    }
    if (!response.ok) {
      throw new ApiError(
        response.status,
        data?.code ?? "error",
        data?.detail ?? data?.message ?? response.statusText ?? `HTTP ${response.status}`,
      );
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

  /** Execute an API request authenticated as a paired KDS device with HMAC-SHA256 v2 request signing. */
  async callAsKitchenDevice<T>(device: DeviceCredential, path: string, options: CallOptions = {}): Promise<T> {
    const method = options.method ?? (options.body === undefined ? "GET" : "POST");
    const body = options.body === undefined ? "" : JSON.stringify(options.body);
    const headers: Record<string, string> = {
      Accept: "application/json",
      "X-Elepay-Api-Key": device.apiKey,
    };
    if (options.body !== undefined) {
      headers["Content-Type"] = "application/json";
    }
    if (method !== "GET") {
      const normalizedPath = path.startsWith("/") ? path : `/${path}`;
      const qIndex = normalizedPath.indexOf("?");
      const pathname = qIndex >= 0 ? normalizedPath.slice(0, qIndex) : normalizedPath;
      const query = qIndex >= 0 ? normalizedPath.slice(qIndex + 1) : null;
      const timestamp = String(Math.floor((Date.now() + this.clockOffsetMs) / 1000));
      const nonce = newKey();
      headers["X-Elepay-Timestamp"] = timestamp;
      headers["X-Elepay-Nonce"] = nonce;
      headers["X-Elepay-Signature-Version"] = "2";
      headers["X-Elepay-Signature"] = hmacSha256Hex(
        device.signingSecret,
        canonicalV2([timestamp, method, pathname, query || null, nonce, null, body]),
      );
    }

    const url = `${this.baseUrl}${path.startsWith("/") ? path : `/${path}`}`;
    const response = await fetch(url, {
      method,
      headers,
      body: options.body === undefined ? undefined : body,
    });
    const text = await response.text();
    let data: { code?: string; detail?: string; message?: string } | null = null;
    if (text) {
      try {
        data = JSON.parse(text) as { code?: string; detail?: string; message?: string };
      } catch {
        throw new ApiError(response.status, "invalid_response", response.statusText || "Invalid server response");
      }
    }
    if (!response.ok) {
      throw new ApiError(
        response.status,
        data?.code ?? "error",
        data?.message ?? data?.detail ?? response.statusText ?? `HTTP ${response.status}`,
      );
    }
    return data as T;
  }
}
