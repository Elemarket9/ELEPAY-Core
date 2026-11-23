import https from "node:https";
import fs from "node:fs";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

/**
 * Optional LAN-only order coordinator. Financial authority always remains ELEPAY checkout.
 * This service cannot authorize NFC/cards/MoMo or credit merchant balances.
 */
const STATUS = ["NEW", "PREPARING", "READY", "SERVED"];
const CHANNELS = new Set(["RETAIL", "DINE_IN", "TAKEOUT", "DELIVERY"]);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const hash = (s) => crypto.createHash("sha256").update(s).digest("hex");
export class RelayError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
const assert = (condition, status, message) => { if (!condition) throw new RelayError(status, message); };

export class LanOrderJournal {
  constructor(filename, merchantId) {
    assert(UUID.test(merchantId), 500, "Configure a valid merchant UUID");
    this.filename = filename;
    this.merchantId = merchantId;
    this.rows = new Map();
    this.listeners = new Set();
    this.sequence = 0;
    this.lastHash = "GENESIS";
    fs.mkdirSync(fileURLToPath(new URL(".", "file://" + (filename.startsWith("/") ? "" : "/") + filename)), { recursive: true });
    if (!fs.existsSync(filename)) fs.writeFileSync(filename, "", { flag: "wx", mode: 0o600 });
    const raw = fs.readFileSync(filename, "utf8");
    if (raw && !raw.endsWith("\n"))
      throw new Error("LAN journal has an incomplete record; do not start before operator recovery");
    for (const line of raw.split("\n").filter(Boolean)) {
      const event = JSON.parse(line);
      const { digest, ...body } = event;
      assert(event.seq === this.sequence + 1 && event.previous === this.lastHash,
        500, "LAN journal sequence broken");
      assert(digest === hash(JSON.stringify(body)), 500, "LAN journal integrity check failed");
      this.apply(event);
      this.sequence = event.seq;
      this.lastHash = digest;
    }
  }

  append(kind, data) {
    const body = { seq: this.sequence + 1, previous: this.lastHash, kind, data };
    const event = { ...body, digest: hash(JSON.stringify(body)) };
    const fd = fs.openSync(this.filename, "a", 0o600);
    try { fs.writeSync(fd, JSON.stringify(event) + "\n"); fs.fsyncSync(fd); }
    finally { fs.closeSync(fd); }
    this.apply(event);
    this.sequence = event.seq; this.lastHash = event.digest;
    this.publish();
  }

  apply(event) {
    if (event.kind === "ORDER") {
      this.rows.set(event.data.offlineTxId, {
        payload: event.data, digest: hash(JSON.stringify(event.data)), status: "NEW",
        actions: {}, serverOrderId: null, createdAt: new Date().toISOString(),
      });
    } else if (event.kind === "ADVANCE") {
      const row = this.rows.get(event.data.id);
      assert(!!row, 500, "Advance refers to unknown order");
      row.status = event.data.to; row.actions[event.data.actionId] = row.status;
    } else if (event.kind === "CLOUD_LINK") {
      const row = this.rows.get(event.data.id);
      assert(!!row, 500, "Cloud link refers to unknown order");
      row.serverOrderId = event.data.serverOrderId;
    } else throw new Error("Unknown LAN journal event");
  }

  accept(payload) {
    assert(payload && payload.merchantId === this.merchantId, 404, "Merchant not found");
    assert(typeof payload.offlineTxId === "string" && UUID.test(payload.offlineTxId)
      && typeof payload.idempotencyKey === "string" && UUID.test(payload.idempotencyKey),
      400, "Order needs stable UUID references");
    assert(CHANNELS.has(payload.orderType), 400, "Unsupported local order channel");
    assert(Array.isArray(payload.items) && payload.items.length >= 1 && payload.items.length <= 100,
      400, "Order needs 1-100 items");
    assert(Number.isSafeInteger(payload.totalPesewas) && payload.totalPesewas > 0,
      400, "Invalid order total");
    assert(payload.payments == null || (Array.isArray(payload.payments)
      && payload.payments.every(p => p.tenderType === "CASH"
        && Number.isSafeInteger(p.amountPesewas) && p.amountPesewas > 0)),
      422, "Only locally collected cash may be represented");
    assert(payload.items.every(i => typeof i.itemName === "string" && i.itemName.length <= 128
      && Number.isFinite(i.quantity) && i.quantity > 0
      && Number.isSafeInteger(i.unitPricePesewas) && i.unitPricePesewas >= 0),
      400, "Invalid menu item");
    const current = this.rows.get(payload.offlineTxId);
    const fingerprint = hash(JSON.stringify(payload));
    if (current) {
      assert(current.digest === fingerprint, 409, "Duplicate offline transaction contains different details");
      return this.snapshot(current);
    }
    this.append("ORDER", payload);
    return this.snapshot(this.rows.get(payload.offlineTxId));
  }

  advance(id, actionId, expected) {
    const row = this.rows.get(id);
    assert(!!row, 404, "Local kitchen ticket not found");
    assert(typeof actionId === "string" && UUID.test(actionId), 400, "Invalid action ID");
    if (Object.hasOwn(row.actions, actionId)) return this.snapshot(row);
    assert(row.status === expected, 409, "Ticket changed; reload before bumping");
    const next = STATUS[STATUS.indexOf(expected) + 1];
    assert(!!next, 409, "Already served");
    this.append("ADVANCE", { id, actionId, from: expected, to: next });
    return this.snapshot(this.rows.get(id));
  }

  link(id, serverOrderId) {
    const row = this.rows.get(id);
    assert(!!row, 404, "Local kitchen ticket not found");
    assert(typeof serverOrderId === "string" && UUID.test(serverOrderId), 400, "Invalid server order ID");
    if (row.serverOrderId) {
      assert(row.serverOrderId === serverOrderId, 409, "Cannot relink to another financial order");
      return this.snapshot(row);
    }
    this.append("CLOUD_LINK", { id, serverOrderId });
    return this.snapshot(this.rows.get(id));
  }

  snapshot(row) {
    return { ...row.payload, status: row.status, serverOrderId: row.serverOrderId,
      createdAt: row.createdAt };
  }
  all() {
    return [...this.rows.values()].map(row => this.snapshot(row));
  }
  subscribe(listener) { this.listeners.add(listener); return () => this.listeners.delete(listener); }
  publish() { const body = this.all(); for (const listener of this.listeners) listener(body); }
}

function json(res, status, value, origin) {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store", ...(origin ? { "Access-Control-Allow-Origin": origin, Vary: "Origin" } : {}) });
  res.end(JSON.stringify(value));
}
const readBody = async (req) => {
  let size = 0; const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 65536) throw new RelayError(413, "LAN order exceeds 64 KiB");
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString("utf8")); }
  catch { throw new RelayError(400, "Invalid JSON"); }
};
const sha = s => crypto.createHash("sha256").update(s).digest();
const constantTimeToken = (given, expected) =>
  crypto.timingSafeEqual(sha(given || ""), sha(expected));

export function makeLanHandler(journal, token, allowedOrigin) {
  assert(typeof token === "string" && token.length >= 32, 500, "Edge bearer token must be at least 32 chars");
  return async (req, res) => {
    const origin = req.headers.origin === allowedOrigin ? allowedOrigin : null;
    if (req.method === "OPTIONS") {
      if (!origin) return json(res, 403, { error: "CORS origin rejected" }, null);
      res.writeHead(204, { "Access-Control-Allow-Origin": origin, Vary: "Origin",
        "Access-Control-Allow-Headers": "Authorization, Content-Type",
        "Access-Control-Allow-Methods": "GET,POST,OPTIONS", "Access-Control-Max-Age": "300" });
      return res.end();
    }
    try {
      const url = new URL(req.url || "/", "https://edge.invalid");
      if (req.method === "GET" && url.pathname === "/lan/health")
        return json(res, 200, { ready: true }, origin);
      if (req.headers.origin && !origin) throw new RelayError(403, "Origin not allowed");
      const bearer = (req.headers.authorization || "").replace(/^Bearer /, "");
      if (!constantTimeToken(bearer, token)) throw new RelayError(401, "Pair this device with the restaurant LAN edge");
      if (req.method === "GET" && url.pathname === "/lan/orders")
        return json(res, 200, journal.all(), origin);
      if (req.method === "GET" && url.pathname === "/lan/orders/stream") {
        res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-store",
          Connection: "keep-alive", ...(origin ? { "Access-Control-Allow-Origin": origin, Vary: "Origin" } : {}) });
        res.write("data: " + JSON.stringify(journal.all()) + "\n\n");
        const off = journal.subscribe(rows => res.write("data: " + JSON.stringify(rows) + "\n\n"));
        const heartbeat = setInterval(() => res.write(": heartbeat\n\n"), 15000);
        req.on("close", () => { clearInterval(heartbeat); off(); });
        return;
      }
      if (req.method === "POST" && url.pathname === "/lan/orders")
        return json(res, 200, journal.accept(await readBody(req)), origin);
      const match = url.pathname.match(/^\/lan\/orders\/([0-9a-f-]{36})\/(advance|link)$/i);
      if (req.method === "POST" && match) {
        const body = await readBody(req);
        return json(res, 200, match[2] === "advance"
          ? journal.advance(match[1], body.actionId, body.from)
          : journal.link(match[1], body.serverOrderId), origin);
      }
      throw new RelayError(404, "LAN route not found");
    } catch (err) {
      json(res, err instanceof RelayError ? err.status : 500,
        { error: err instanceof RelayError ? err.message : "Local edge operation failed" }, origin);
    }
  };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const required = ["ELEPAY_EDGE_TOKEN", "ELEPAY_EDGE_MERCHANT", "ELEPAY_EDGE_CERT", "ELEPAY_EDGE_KEY", "ELEPAY_EDGE_ORIGIN", "ELEPAY_EDGE_DATA"];
  for (const key of required) if (!process.env[key]) throw new Error(key + " is required");
  const journal = new LanOrderJournal(process.env.ELEPAY_EDGE_DATA, process.env.ELEPAY_EDGE_MERCHANT);
  const listener = makeLanHandler(journal, process.env.ELEPAY_EDGE_TOKEN, process.env.ELEPAY_EDGE_ORIGIN);
  const server = https.createServer({ cert: fs.readFileSync(process.env.ELEPAY_EDGE_CERT),
    key: fs.readFileSync(process.env.ELEPAY_EDGE_KEY) }, listener);
  const port = Number(process.env.ELEPAY_EDGE_PORT || 8443);
  server.listen(port, "0.0.0.0", () => {
    process.stdout.write("ELEPAY LAN edge ready on HTTPS port " + port + "\n");
  });
}
