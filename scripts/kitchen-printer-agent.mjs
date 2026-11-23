import crypto from "node:crypto";
import net from "node:net";
import { fileURLToPath } from "node:url";

/** Printer destinations are operator configured, never chosen by a customer request. */
export function parseAllowedPrinter(address, allowlist) {
  const location = new URL(address);
  if (location.protocol !== "tcp:") throw new Error("PRINTER_TRANSPORT_NOT_CONFIGURED: use TCP or add certified USB/Bluetooth adapter");
  const host = location.hostname;
  if (net.isIP(host) !== 4) throw new Error("Printer requires an explicit private IPv4 address");
  const octets = host.split(".").map(Number);
  const privateIp = octets[0] === 10 || octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31
    || octets[0] === 192 && octets[1] === 168;
  if (!privateIp || !allowlist.includes(host))
    throw new Error("PRINTER_NOT_ALLOWED: choose an allowed private LAN address");
  if (location.username || location.password || location.pathname !== "/" || location.search || location.hash)
    throw new Error("Invalid printer address");
  const port = location.port ? Number(location.port) : 9100;
  if (!Number.isSafeInteger(port) || port < 1024 || port > 65535) throw new Error("Invalid printer port");
  return { host, port };
}

/** ESC/POS init, readable ticket text, line feed and cut. Printer firmware must support UTF-8. */
export function encodeEscPos(content) {
  if (typeof content !== "string" || !content.length || content.length > 32768)
    throw new Error("Invalid thermal printer ticket");
  const safe = content.replace(/\0/g, "").replace(/\r\n?/g, "\n");
  return Buffer.concat([
    Buffer.from([0x1b, 0x40]),
    Buffer.from(safe + "\n\n\n", "utf8"),
    Buffer.from([0x1d, 0x56, 0x00]),
  ]);
}

export async function printTcp(address, content, allowlist) {
  const destination = parseAllowedPrinter(address, allowlist);
  const bytes = encodeEscPos(content);
  await new Promise((resolve, reject) => {
    const socket = net.createConnection(destination);
    let settled = false;
    const finish = (e) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      if (e) reject(e); else resolve();
    };
    socket.setTimeout(5000);
    socket.on("connect", () => socket.end(bytes));
    socket.on("timeout", () => finish(new Error("Printer network timeout")));
    socket.on("error", finish);
    socket.on("close", (hadError) => { if (!hadError) finish(null); });
  });
}

function canonicalV2(fields) {
  return "elepay-request:v2" + fields.map((value) => {
    const text = value ?? "";
    return ":" + Buffer.byteLength(text) + ":" + text;
  }).join("");
}

export function signedHeaders(pathname, query, method, body, apiKey, signingSecret) {
  const timestamp = String(Math.floor(Date.now()/1000));
  const nonce = crypto.randomUUID();
  const canonical = canonicalV2([timestamp, method, pathname, query || null, nonce, null, body]);
  return {
    Accept: "application/json", "X-Elepay-Api-Key": apiKey,
    "X-Elepay-Timestamp": timestamp, "X-Elepay-Nonce": nonce,
    "X-Elepay-Signature-Version": "2",
    "X-Elepay-Signature": crypto.createHmac("sha256", signingSecret).update(canonical).digest("hex"),
    ...(body ? { "Content-Type": "application/json" } : {}),
  };
}

export async function printerApi(base, path, method, body, config) {
  const root = new URL(base);
  if (root.protocol !== "https:" || root.pathname !== "/")
    throw new Error("Cloud API must use a trusted HTTPS origin");
  const url = new URL(path, root);
  const payload = body == null ? "" : JSON.stringify(body);
  const response = await fetch(url, {
    method, headers: signedHeaders(url.pathname, url.search.slice(1), method, payload,
      config.apiKey, config.signingSecret),
    ...(body == null ? {} : { body: payload }),
  });
  if (response.status === 204) return null;
  if (!response.ok) throw new Error("Kitchen print queue request failed: HTTP " + response.status);
  return response.json();
}

export async function processPrintJob(job, config, transport = printTcp) {
  if (job.merchantId !== config.merchantId)
    throw new Error("Printer job merchant mismatch");
  try {
    await transport(job.printerAddress, job.content, config.allowedHosts);
    return { deviceId: config.deviceId, printed: true, error: null };
  } catch (e) {
    return { deviceId: config.deviceId, printed: false,
      error: e instanceof Error ? e.message.slice(0, 300) : "Printer failed" };
  }
}

export async function printerAgent(config) {
  const qs = "?merchantId=" + encodeURIComponent(config.merchantId) +
    "&deviceId=" + encodeURIComponent(config.deviceId);
  for (;;) {
    try {
      const job = await printerApi(config.origin, "/v1/restaurant/kds/print-jobs/claim" + qs,
        "POST", null, config);
      if (job) {
        const result = await processPrintJob(job, config);
        await printerApi(config.origin, "/v1/restaurant/kds/print-jobs/" +
          encodeURIComponent(job.id) + "/result", "POST", result, config);
      }
    } catch (e) {
      process.stderr.write("Print bridge: " + (e instanceof Error ? e.message : "error") + "\n");
    }
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const env = process.env;
  const required = ["ELEPAY_KDS_ORIGIN","ELEPAY_KDS_MERCHANT","ELEPAY_KDS_DEVICE_ID",
    "ELEPAY_KDS_API_KEY","ELEPAY_KDS_SIGNING_SECRET","ELEPAY_PRINTER_ALLOWED_HOSTS"];
  for (const name of required) if (!env[name]) throw new Error(name + " is required");
  void printerAgent({
    origin: env.ELEPAY_KDS_ORIGIN, merchantId: env.ELEPAY_KDS_MERCHANT,
    deviceId: env.ELEPAY_KDS_DEVICE_ID,
    apiKey: env.ELEPAY_KDS_API_KEY, signingSecret: env.ELEPAY_KDS_SIGNING_SECRET,
    allowedHosts: env.ELEPAY_PRINTER_ALLOWED_HOSTS.split(",").map((x) => x.trim()),
  });
}
