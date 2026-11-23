/**
 * Trusted HTTPS LAN edge transport. Credentials are session-only and never embedded
 * in published JavaScript, cloud APIs, checkout links or order payloads.
 */
import { offlinePayload, type OfflineOrder } from "./offline-pos";

export interface LanSettings { url: string; token: string }
export interface LanTicket {
  offlineTxId: string; merchantId: string; orderType: string; tableId: string | null;
  items: { itemName: string; quantity: number; notes?: string | null }[];
  totalPesewas: number; payments: { tenderType: "CASH"; amountPesewas: number }[];
  status: "NEW" | "PREPARING" | "READY" | "SERVED";
  serverOrderId: string | null; createdAt: string;
}

const KEY = "elepay.lan.session";
export function readLanSettings(): LanSettings | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    return raw ? JSON.parse(raw) as LanSettings : null;
  } catch { return null; }
}
export function saveLanSettings(url: string, token: string): LanSettings {
  const parsed = new URL(url);
  if (parsed.protocol !== "https:" || parsed.username || parsed.password || parsed.search || parsed.hash)
    throw new Error("LAN edge requires a trusted HTTPS address without URL credentials");
  if (token.length < 32) throw new Error("LAN pairing token must be at least 32 characters");
  const settings = { url: parsed.origin, token };
  sessionStorage.setItem(KEY, JSON.stringify(settings));
  return settings;
}

async function request<T>(settings: LanSettings, pathname: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const response = await fetch(settings.url + pathname, {
    method: body === undefined ? "GET" : "POST", signal,
    headers: { Authorization: "Bearer " + settings.token,
      Accept: "application/json", ...(body === undefined ? {} : { "Content-Type": "application/json" }) },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: "no-store",
  });
  if (!response.ok) throw new Error("LAN edge returned " + response.status);
  return await response.json() as T;
}
export function publishLanOrder(settings: LanSettings, order: OfflineOrder): Promise<LanTicket> {
  return request(settings, "/lan/orders", offlinePayload(order));
}
export function linkLanOrder(settings: LanSettings, id: string, serverOrderId: string): Promise<LanTicket> {
  return request(settings, "/lan/orders/" + encodeURIComponent(id) + "/link", { serverOrderId });
}
export function bumpLanOrder(settings: LanSettings, id: string, from: LanTicket["status"]): Promise<LanTicket> {
  return request(settings, "/lan/orders/" + encodeURIComponent(id) + "/advance",
    { from, actionId: crypto.randomUUID() });
}
export function listLanOrders(settings: LanSettings): Promise<LanTicket[]> {
  return request(settings, "/lan/orders");
}

/** Streams updates with Authorization without leaking credentials in query strings. */
export function streamLanOrders(settings: LanSettings, onOrders: (orders: LanTicket[]) => void,
  onFailure: (reason: string) => void): () => void {
  const abort = new AbortController();
  const run = async () => {
    while (!abort.signal.aborted) {
      try {
        const response = await fetch(settings.url + "/lan/orders/stream", {
          headers: { Authorization: "Bearer " + settings.token },
          cache: "no-store", signal: abort.signal,
        });
        if (!response.ok || !response.body) throw new Error("LAN stream unavailable: " + response.status);
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, "\n");
          let boundary = buffer.indexOf("\n\n");
          while (boundary !== -1) {
            const event = buffer.slice(0, boundary);
            buffer = buffer.slice(boundary + 2);
            for (const line of event.split("\n")) {
              if (line.startsWith("data: ")) {
                const orders = JSON.parse(line.slice(6)) as LanTicket[];
                onOrders(orders);
              }
            }
            boundary = buffer.indexOf("\n\n");
          }
          if (buffer.length > 262144) throw new Error("LAN stream frame exceeded safe size");
        }
      } catch (error) {
        if (!abort.signal.aborted) onFailure(error instanceof Error ? error.message : "LAN stream interrupted");
      }
      if (abort.signal.aborted) break;
      await new Promise<void>((resolve) => setTimeout(resolve, 1500));
    }
  };
  void run();
  return () => abort.abort();
}
