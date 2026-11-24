import { api, ApiError, safeApiPath, type CallOptions } from "../api";

/**
 * A paired kitchen device. Pairing gives the device its own kitchen-only key, so it keeps working after
 * restarts without anyone signing in. The key can only reach the kitchen display and can be revoked from
 * the merchant dashboard.
 */
export interface DeviceCredential {
  apiKey: string; signingSecret: string; deviceId: string; merchantId: string; merchantName: string;
  stationId: string | null; stationName: string | null;
}

const DEVICE_KEY = "elepay.kds.device";

export function loadDevice(): DeviceCredential | null {
  try { const raw = window.localStorage.getItem(DEVICE_KEY); return raw ? JSON.parse(raw) as DeviceCredential : null; } catch { return null; }
}

export function saveDevice(d: DeviceCredential | null) {
  try { if (d) window.localStorage.setItem(DEVICE_KEY, JSON.stringify(d)); else window.localStorage.removeItem(DEVICE_KEY); } catch { /* ignore */ }
}

export async function pairDevice(merchantCode: string, code: string, deviceName: string): Promise<DeviceCredential> {
  return api<DeviceCredential>("/v1/public/kitchen/pair", { body: { merchantCode, code, deviceName }, anonymous: true });
}

/** Server time minus device time, learned from sync responses. Tablets often have the wrong clock. */
let clockOffsetMs = 0;
export function learnServerTime(serverTime: string) {
  const t = Date.parse(serverTime);
  if (!Number.isNaN(t)) clockOffsetMs = t - Date.now();
}

export type KitchenCall = <T>(path: string, options?: CallOptions) => Promise<T>;

/** API calls as the signed-in staff member, or as the paired device (signed, like other API keys). */
export function kitchenClient(device: DeviceCredential | null): KitchenCall {
  if (!device) return api;
  return async <T,>(path: string, options: CallOptions = {}): Promise<T> => {
    if(!safeApiPath(path)||!path.startsWith("/v1/restaurant/kds/"))throw new ApiError(403,"invalid_device_path","Kitchen device requests are restricted to kitchen endpoints.");
    const method = (options.method ?? (options.body === undefined ? "GET" : "POST")).toUpperCase();
    const body = options.body === undefined ? "" : JSON.stringify(options.body);
    const headers: Record<string, string> = { Accept: "application/json", "X-Elepay-Api-Key": device.apiKey };
    if (options.body !== undefined) headers["Content-Type"] = "application/json";
    if(options.idempotencyKey)headers["Idempotency-Key"]=options.idempotencyKey;
    if (method !== "GET") {
      const url = new URL(path, window.location.origin);
      const timestamp = String(Math.floor((Date.now() + clockOffsetMs) / 1000));
      const nonce = crypto.randomUUID();
      headers["X-Elepay-Timestamp"] = timestamp;
      headers["X-Elepay-Nonce"] = nonce;
      headers["X-Elepay-Signature-Version"] = "2";
      headers["X-Elepay-Signature"] = await sign(device.signingSecret,
        canonicalV2([timestamp, method, url.pathname, url.search.replace(/^\?/, "") || null, nonce, options.idempotencyKey??null, body]));
    }
    const response = await fetch(path, { method, headers, cache:"no-store", redirect:"error", credentials:"omit", signal:options.signal??AbortSignal.timeout(30000), body: options.body === undefined ? undefined : body });
    const text = await response.text();
    const data = text ? JSON.parse(text) : null;
    if (!response.ok) throw new ApiError(response.status, data?.code ?? "error", data?.message ?? data?.detail ?? response.statusText);
    return data as T;
  };
}

/** Same canonical form as the server's RequestSigningFilter (version 2): each field as :byteLength:value. */
export function canonicalV2(fields: (string | null)[]): string {
  const enc = new TextEncoder();
  return "elepay-request:v2" + fields.map((f) => { const v = f ?? ""; return `:${enc.encode(v).length}:${v}`; }).join("");
}

export async function sign(secret: string, payload: string): Promise<string> {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = new Uint8Array(await crypto.subtle.sign("HMAC", key, enc.encode(payload)));
  return Array.from(mac, (b) => b.toString(16).padStart(2, "0")).join("");
}
