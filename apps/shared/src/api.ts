import { canonicalV2, hmacSha256Hex } from "./hmac";
import type {
  DeviceCredential,
  PushDeviceListResponse,
  PushDeviceRegistration,
  RegisteredPushDevice,
} from "./types";

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

  /** Native registration: sends separate 6-digit verification codes to both Email and SMS. */
  async registerAccount(input: {
    fullName: string;
    email: string;
    phone: string;
    password: string;
  }): Promise<{
    challengeId: string;
    purpose: string;
    channels: string[];
    maskedEmail: string | null;
    maskedPhone: string | null;
    expiresInSeconds: number;
    emailVerified: boolean;
    smsVerified: boolean;
  }> {
    return this.call("/v1/auth/register", {
      method: "POST",
      anonymous: true,
      body: input,
    });
  }

  /** Native sign-in: verifies password and sends 6-digit verification codes to both Email and SMS. */
  async loginWithPassword(identifier: string, password: string): Promise<{
    challengeId: string;
    purpose: string;
    channels: string[];
    maskedEmail: string | null;
    maskedPhone: string | null;
    expiresInSeconds: number;
    emailVerified: boolean;
    smsVerified: boolean;
  }> {
    return this.call("/v1/auth/login", {
      method: "POST",
      anonymous: true,
      body: { identifier: identifier.trim(), password },
    });
  }

  /** Verifies both Email and SMS 6-digit codes and stores the issued Bearer token when complete. */
  async verifyEmailAndSms(
    challengeId: string,
    emailCode: string,
    smsCode: string,
  ): Promise<{
    verified: boolean;
    emailVerified: boolean;
    smsVerified: boolean;
    accessToken: string | null;
    tokenType: string | null;
    expiresInSeconds: number;
  }> {
    const res = await this.call<{
      verified: boolean;
      emailVerified: boolean;
      smsVerified: boolean;
      accessToken: string | null;
      tokenType: string | null;
      expiresInSeconds: number;
    }>("/v1/auth/verify", {
      method: "POST",
      anonymous: true,
      body: { challengeId, emailCode: emailCode.trim(), smsCode: smsCode.trim() },
    });
    if (res.verified && res.accessToken) {
      this.token = res.accessToken;
    }
    return res;
  }

  /** Resends fresh Email and SMS verification codes for an active auth challenge. */
  async resendVerificationCodes(challengeId: string): Promise<{
    challengeId: string;
    purpose: string;
    channels: string[];
    maskedEmail: string | null;
    maskedPhone: string | null;
    expiresInSeconds: number;
    emailVerified: boolean;
    smsVerified: boolean;
  }> {
    return this.call("/v1/auth/resend", {
      method: "POST",
      anonymous: true,
      body: { challengeId },
    });
  }

  /** Requests password reset codes sent to both Email and SMS. */
  async requestPasswordReset(identifier: string): Promise<{
    challengeId: string;
    purpose: string;
    channels: string[];
    maskedEmail: string | null;
    maskedPhone: string | null;
    expiresInSeconds: number;
    emailVerified: boolean;
    smsVerified: boolean;
  }> {
    return this.call("/v1/auth/forgot-password", {
      method: "POST",
      anonymous: true,
      body: { identifier: identifier.trim() },
    });
  }

  /** Completes password reset using both Email and SMS 6-digit codes. */
  async resetPassword(input: {
    challengeId: string;
    emailCode: string;
    smsCode: string;
    newPassword: string;
  }): Promise<{ reset: boolean }> {
    return this.call("/v1/auth/reset-password", {
      method: "POST",
      anonymous: true,
      body: input,
    });
  }

  /** Confirm a settlement destination using the 6-digit Email/SMS OOB code. */
  async confirmDestinationOob(merchantId: string, destinationId: string, token: string): Promise<void> {
    await this.call(`/v1/merchants/${merchantId}/settlement-destinations/${destinationId}/confirm-oob`, {
      method: "POST",
      body: { token: token.trim() },
    });
  }

  /** Resend a fresh 6-digit Email+SMS OOB code for a pending settlement destination. */
  async resendDestinationOob(merchantId: string, destinationId: string): Promise<void> {
    await this.call(`/v1/merchants/${merchantId}/settlement-destinations/${destinationId}/resend-oob`, {
      method: "POST",
    });
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

  /** Register or refresh a Firebase Cloud Messaging (FCM) device token for push notifications. */
  async registerPushDevice(input: PushDeviceRegistration): Promise<RegisteredPushDevice> {
    return this.call<RegisteredPushDevice>("/v1/notifications/devices", {
      method: "POST",
      body: {
        token: input.token.trim(),
        platform: input.platform,
        appId: input.appId,
        merchantId: input.merchantId ?? null,
      },
    });
  }

  /** List the authenticated user's active registered FCM push devices (tokens are masked). */
  async listPushDevices(): Promise<PushDeviceListResponse> {
    return this.call<PushDeviceListResponse>("/v1/notifications/devices", {
      method: "GET",
    });
  }

  /** Unregister an FCM device token on sign-out so this device stops receiving push alerts. */
  async unregisterPushDevice(token: string): Promise<{ unregistered: boolean }> {
    return this.call<{ unregistered: boolean }>("/v1/notifications/devices", {
      method: "DELETE",
      body: { token: token.trim() },
    });
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
