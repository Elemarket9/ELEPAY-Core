import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ApiError, newKey } from "./api";
import { getToken } from "./auth";
import { kitchenClient, learnServerTime, loadDevice, pairDevice, saveDevice, type DeviceCredential, type KitchenCall } from "./kds/device";
import {
  applySync, backoff, bump, cancel, elapsed, load, save, settle, stationStatus, storageKey, urgency, visible,
  nextStatus, type BumpResult, type KdsState, type QueuedAction, type Status, type SyncResult, type TicketView,
} from "./kds/engine";

interface MerchantView { id: string; tradingName: string; role: string }
interface Station { id: string; name: string; output: "SCREEN" | "PRINTER" | "BOTH"; printerAddress: string | null }
interface Setup { merchantId: string; merchantName: string; stationId: string | null; stationName: string; hasPrinter: boolean }

const SETUP_KEY = "elepay.kds.setup";
const BASE = "/v1/restaurant/kds";

function store(): Storage | null {
  try { return window.localStorage; } catch { return null; }
}

function readSetup(): Setup | null {
  try { const raw = store()?.getItem(SETUP_KEY); return raw ? JSON.parse(raw) as Setup : null; } catch { return null; }
}

/** Full-screen kitchen display for tablets and POS screens. Lives at /kitchen. */
export function Kitchen() {
  const [device, setDevice] = useState<DeviceCredential | null>(loadDevice);
  const [staff, setStaff] = useState<boolean | null>(null);
  const [setup, setSetup] = useState<Setup | null>(() => {
    const saved = readSetup();
    const d = loadDevice();
    if (d && saved && saved.merchantId !== d.merchantId) return null;
    if (d && !saved && d.stationId !== undefined && d.stationName) {
      return { merchantId: d.merchantId, merchantName: d.merchantName, stationId: d.stationId, stationName: d.stationName, hasPrinter: false };
    }
    return saved;
  });
  const [started, setStarted] = useState(false);
  const client = useMemo(() => kitchenClient(device), [device]);
  useEffect(() => { void getToken().then((t) => setStaff(!!t)); }, []);

  useEffect(() => {
    // Lets the tablet reopen the screen without internet after a power cut.
    if ("serviceWorker" in navigator) {
      navigator.serviceWorker.register("/kitchen-sw.js", { scope: "/kitchen" })
        .then(() => navigator.serviceWorker.ready)
        .then((reg) => {
          // Save every app file this page has loaded, so the screen opens after a restart with no internet.
          const files = performance.getEntriesByType("resource").map((e) => new URL(e.name).pathname)
            .filter((p) => p.startsWith("/assets/") || p === "/favicon.svg");
          reg.active?.postMessage({ type: "cache", files });
        })
        .catch(() => {});
    }
    if (!document.querySelector('link[rel="manifest"]')) {
      const link = document.createElement("link");
      link.rel = "manifest"; link.href = "/kitchen.webmanifest";
      document.head.appendChild(link);
    }
    document.body.classList.add("kitchen-body");
    return () => document.body.classList.remove("kitchen-body");
  }, []);

  const unpair = () => {
    saveDevice(null); setDevice(null);
    try { store()?.removeItem(SETUP_KEY); } catch { /* ignore */ }
    setSetup(null); setStarted(false);
  };
  if (!device && staff === null) return null;
  if (!device && !staff) return <PairScreen onPaired={(d) => {
    saveDevice(d); setDevice(d);
    if (d.stationId && d.stationName) {
      const s: Setup = { merchantId: d.merchantId, merchantName: d.merchantName, stationId: d.stationId, stationName: d.stationName, hasPrinter: false };
      try { store()?.setItem(SETUP_KEY, JSON.stringify(s)); } catch { /* ignore */ }
      setSetup(s);
    } else {
      setSetup(null);
    }
  }} />;
  if (!setup) return <SetupScreen client={client} device={device} onDone={(s) => { try { store()?.setItem(SETUP_KEY, JSON.stringify(s)); } catch { /* ignore */ } setSetup(s); }} />;
  if (!started) {
    return (
      <main className="kitchen-start">
        <h1>{setup.merchantName}</h1>
        <p className="kitchen-station-name">{setup.stationName}</p>
        <button className="kitchen-big" onClick={() => { unlockAudio(); void keepAwake(); setStarted(true); }}>Start kitchen screen</button>
        <button className="link" onClick={() => { try { store()?.removeItem(SETUP_KEY); } catch { /* ignore */ } setSetup(null); }}>
          Change station
        </button>
        {device && <p className="muted small">Paired device: {device.merchantName}. <button className="link" onClick={unpair}>Unpair</button></p>}
      </main>
    );
  }
  return <KitchenScreen setup={setup} client={client} paired={!!device} onUnpair={unpair} />;
}

/** First run on a kitchen tablet: pair it with the codes a manager shows in the dashboard. */
function PairScreen({ onPaired }: { onPaired: (d: DeviceCredential) => void }) {
  const [merchantCode, setMerchantCode] = useState("");
  const [code, setCode] = useState("");
  const [name, setName] = useState("Kitchen tablet");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <main className="kitchen-start">
      <h1>Pair this kitchen screen</h1>
      <p>In the ELEPAY merchant dashboard, open <b>Restaurant &amp; KDS → Kitchen Display System</b> and tap <b>Pair a kitchen device</b>.
        Enter the two codes shown there. You only do this once.</p>
      {error && <p className="error">{error}</p>}
      <form onSubmit={async (e) => {
        e.preventDefault(); setBusy(true); setError(null);
        try { onPaired(await pairDevice(merchantCode.trim(), code.replace(/\s/g, ""), name)); }
        catch (err) { setError(err instanceof ApiError && err.status === 400 ? "Those codes did not work. Check them, or ask the manager for a new pairing code." : "Could not reach ELEPAY. Check the internet and try again."); }
        finally { setBusy(false); }
      }}>
        <label>Merchant code<input inputMode="numeric" pattern="\d{6}" maxLength={6} value={merchantCode} onChange={(e) => setMerchantCode(e.target.value)} required /></label>
        <label>Pairing code<input inputMode="numeric" pattern="[\d ]{6,7}" maxLength={7} value={code} onChange={(e) => setCode(e.target.value)} required /></label>
        <label>Name for this device<input value={name} maxLength={64} onChange={(e) => setName(e.target.value)} /></label>
        <button className="kitchen-big" type="submit" disabled={busy}>{busy ? "Pairing…" : "Pair"}</button>
      </form>
      <p className="muted small">Staff with an ELEPAY login can <a href="/merchant">sign in</a> instead, then come back to /kitchen.</p>
    </main>
  );
}

function SetupScreen({ client, device, onDone }: { client: KitchenCall; device: DeviceCredential | null; onDone: (s: Setup) => void }) {
  const [merchants, setMerchants] = useState<MerchantView[]>([]);
  const [merchantId, setMerchantId] = useState("");
  const [stations, setStations] = useState<Station[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (device) {
      setMerchants([{ id: device.merchantId, tradingName: device.merchantName, role: "device" }]);
      setMerchantId(device.merchantId);
      return;
    }
    client<MerchantView[]>("/v1/merchants").then((m) => { setMerchants(m); if (m[0]) setMerchantId(m[0].id); })
      .catch((e) => setError(e instanceof ApiError && e.status === 401 ? "Sign in first, then come back to /kitchen." : String(e.message ?? e)));
  }, [client, device]);
  useEffect(() => {
    if (!merchantId) return;
    client<Station[]>(`${BASE}/stations/merchant/${merchantId}`).then(setStations).catch((e) => setError(String(e.message ?? e)));
  }, [merchantId, client]);

  const merchant = merchants.find((m) => m.id === merchantId);
  return (
    <main className="kitchen-start">
      <h1>Set up this kitchen screen</h1>
      {error && <p className="error">{error}</p>}
      {merchants.length > 1 && (
        <label>Restaurant
          <select value={merchantId} onChange={(e) => setMerchantId(e.target.value)}>
            {merchants.map((m) => <option key={m.id} value={m.id}>{m.tradingName}</option>)}
          </select>
        </label>
      )}
      {merchant && (
        <div className="kitchen-choices">
          <p>Which station is this screen for?</p>
          <button className="kitchen-big" onClick={() => onDone({ merchantId, merchantName: merchant.tradingName, stationId: null,
            stationName: "All orders (expo / pass)", hasPrinter: false })}>All orders (expo / pass)</button>
          {stations.map((s) => (
            <button key={s.id} className="kitchen-big secondary" onClick={() => onDone({ merchantId, merchantName: merchant.tradingName,
              stationId: s.id, stationName: s.name, hasPrinter: !!s.printerAddress })}>{s.name}</button>
          ))}
          {stations.length === 0 && <p className="muted">No stations yet. A manager can add Kitchen, Grill, Drinks… in Restaurant & KDS.</p>}
        </div>
      )}
    </main>
  );
}

function KitchenScreen({ setup, client, paired, onUnpair }: { setup: Setup; client: KitchenCall; paired: boolean; onUnpair: () => void }) {
  const key = storageKey(setup.merchantId, setup.stationId);
  const [state, setState] = useState<KdsState>(() => load(store(), key));
  const stateRef = useRef(state);
  const [online, setOnline] = useState(true);
  const [signedOut, setSignedOut] = useState(false);
  const [now, setNow] = useState(Date.now());
  const [fresh, setFresh] = useState<Set<string>>(new Set());
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const [confirmCancel, setConfirmCancel] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const update = useCallback((fn: (s: KdsState) => KdsState) => {
    const next = fn(stateRef.current);
    stateRef.current = next;
    save(store(), key, next);
    setState(next);
  }, [key]);

  // Keep pulling changes. Fast when healthy, backing off when the network is down.
  useEffect(() => {
    let stopped = false;
    let failures = 0;
    let timer: number | undefined;
    const run = async () => {
      try {
        let more = true;
        while (more && !stopped) {
          const station = setup.stationId ? `&stationId=${setup.stationId}` : "";
          const result = await client<SyncResult>(`${BASE}/sync?merchantId=${setup.merchantId}&since=${stateRef.current.cursor}${station}`);
          learnServerTime(result.serverTime);
          let arrived: string[] = [];
          update((s) => { const r = applySync(s, result); arrived = r.arrived; return r.state; });
          if (arrived.length) { beep(); setFresh((f) => new Set([...f, ...arrived])); window.setTimeout(() => setFresh(new Set()), 8000); }
          more = result.hasMore;
        }
        failures = 0; setOnline(true); setSignedOut(false);
        await flush();
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) setSignedOut(true);
        failures++; setOnline(false);
      }
      if (!stopped) timer = window.setTimeout(run, backoff(failures));
    };
    const flush = async () => {
      while (stateRef.current.queue.length && !stopped) {
        const action = stateRef.current.queue[0];
        try {
          const result = await send(client, action);
          update((s) => settle(s, action.actionId, result));
          if (!result.applied && result.reason === "STATUS_CHANGED") setNotice(`Order ${num(result.view)} was already updated on another screen.`);
        } catch (e) {
          if (e instanceof ApiError && e.status >= 400 && e.status < 500 && e.status !== 401 && e.status !== 429) {
            update((s) => settle(s, action.actionId, null));   // the server refused it; the next sync shows the truth
            setNotice(`Could not update order: ${e.message}`);
          } else {
            throw e;   // network down: keep it queued
          }
        }
      }
    };
    const kick = () => { window.clearTimeout(timer); void run(); };
    void run();
    window.addEventListener("online", kick);
    return () => { stopped = true; window.clearTimeout(timer); window.removeEventListener("online", kick); };
  }, [setup.merchantId, setup.stationId, update, client]);

  useEffect(() => { const t = window.setInterval(() => setNow(Date.now()), 1000); return () => window.clearInterval(t); }, []);
  useEffect(() => {
    const again = () => { if (document.visibilityState === "visible") void keepAwake(); };
    document.addEventListener("visibilitychange", again);
    return () => document.removeEventListener("visibilitychange", again);
  }, []);
  useEffect(() => { if (!notice) return; const t = window.setTimeout(() => setNotice(null), 6000); return () => window.clearTimeout(t); }, [notice]);

  const tickets = visible(state, setup.stationId);
  const waiting = state.queue.length;
  const lastSync = state.lastSyncAt ? Math.round((now - state.lastSyncAt) / 1000) : null;

  const reprint = async (ticketId: string) => {
    setMenuFor(null);
    try {
      await client(`${BASE}/tickets/${ticketId}/reprint?stationId=${setup.stationId}`, { method: "POST" });
      setNotice("Sent to the printer.");
    } catch (e) {
      setNotice(`Could not reprint: ${e instanceof Error ? e.message : e}`);
    }
  };

  return (
    <div className="kitchen">
      <div className="kitchen-bar">
        <strong>{setup.stationName}</strong>
        <span className="muted">{setup.merchantName}</span>
        <span className="kitchen-count">{tickets.length} open</span>
        <span className={`kitchen-net ${online ? "on" : "off"}`}>
          {online ? "● Online" : "● No internet"}
          {waiting > 0 && ` · ${waiting} update${waiting > 1 ? "s" : ""} waiting to send`}
          {!online && lastSync !== null && ` · last update ${ago(lastSync)}`}
        </span>
        <span className="kitchen-clock">{new Date(now).toLocaleTimeString("en-GH", { hour: "2-digit", minute: "2-digit", timeZone: "Africa/Accra" })}</span>
      </div>
      {signedOut && (paired
        ? <div className="kitchen-alert">This device was removed in the dashboard. <button className="link" onClick={onUnpair}>Pair it again</button></div>
        : <div className="kitchen-alert">This screen has been signed out. Sign in again to keep receiving orders.</div>)}
      {state.printerFailures.map((f) => (
        <div key={f.id} className="kitchen-alert">
          Printer problem at {f.stationName}: {f.lastError ?? "not printing"}. Check paper and power, then reprint.
        </div>
      ))}
      {notice && <div className="kitchen-notice">{notice}</div>}
      {tickets.length === 0 && <p className="kitchen-empty">No open orders</p>}
      <div className="kitchen-grid">
        {tickets.map((v) => {
          const status = stationStatus(v, setup.stationId);
          const next = nextStatus(status);
          const mine = setup.stationId ? v.items.filter((i) => i.stationId === setup.stationId || i.stationId === null) : v.items;
          return (
            <article key={v.ticket.id} className={`kitchen-card ${urgency(v, now)} ${status.toLowerCase()} ${fresh.has(v.ticket.id) ? "fresh" : ""}`}>
              <header>
                <span className="kitchen-num">{num(v)}</span>
                <span className="kitchen-mode">{v.ticket.diningMode.replace("_", " ")}</span>
                {v.ticket.tableNumber && <span className="kitchen-mode">Table {v.ticket.tableNumber}</span>}
                <span className={`kitchen-pay ${(v.ticket.paymentStatus ?? "PENDING").toLowerCase()}`}>{v.ticket.paymentStatus === "PAID" ? "PAID" : v.ticket.paymentStatus === "PARTIAL" ? "PARTIAL" : "UNPAID"}</span>
                <span className="kitchen-time">{elapsed(v, now)}</span>
              </header>
              {v.ticket.serverName && <p className="kitchen-server">{v.ticket.serverName}</p>}
              <ul>
                {mine.filter((i) => i.itemStatus !== "CANCELLED").map((i) => (
                  <li key={i.id} className={i.itemStatus === "READY" || i.itemStatus === "SERVED" ? "done" : ""}>
                    <b>{qty(i.quantity)}×</b> {i.itemName}
                    {i.modifiers && i.modifiers !== "[]" && <span className="kitchen-mod">+ {i.modifiers}</span>}
                  </li>
                ))}
              </ul>
              {v.ticket.notes && <p className="kitchen-note">{v.ticket.notes}</p>}
              <footer>
                {next && !(setup.stationId && next === "SERVED") && (
                  <button className={`kitchen-bump to-${next.toLowerCase()}`}
                    onClick={() => update((s) => bump(s, v.ticket.id, setup.stationId, newKey()))}>{LABEL[next]}</button>
                )}
                <button className="kitchen-more secondary" aria-label="More actions"
                  onClick={() => { setMenuFor(menuFor === v.ticket.id ? null : v.ticket.id); setConfirmCancel(null); }}>⋯</button>
              </footer>
              {menuFor === v.ticket.id && (
                <div className="kitchen-menu">
                  {setup.hasPrinter && <button className="secondary" onClick={() => void reprint(v.ticket.id)}>Reprint</button>}
                  {!paired && (confirmCancel === v.ticket.id
                    ? <button className="danger" onClick={() => { update((s) => cancel(s, v.ticket.id, null, newKey())); setMenuFor(null); }}>Tap again to cancel order</button>
                    : <button className="secondary" onClick={() => setConfirmCancel(v.ticket.id)}>Manager: cancel order</button>)}
                </div>
              )}
            </article>
          );
        })}
      </div>
    </div>
  );
}

const LABEL: Record<Status, string> = { NEW: "", PREPARING: "Start", READY: "Ready", SERVED: "Served", CANCELLED: "" };

function num(v: TicketView) {
  return v.ticket.ticketNumber != null ? `#${String(v.ticket.ticketNumber).padStart(3, "0")}` : "#—";
}

function qty(q: number) { return Number.isInteger(q) ? String(q) : String(Number(q.toFixed(3))); }

function ago(secs: number) {
  if (secs < 60) return `${secs}s ago`;
  if (secs < 3600) return `${Math.floor(secs / 60)} min ago`;
  return `${Math.floor(secs / 3600)} h ago`;
}

function send(client: KitchenCall, a: QueuedAction): Promise<BumpResult> {
  if (a.kind === "cancel") return client<BumpResult>(`${BASE}/tickets/${a.ticketId}/cancel`, { body: { actionId: a.actionId, reason: a.reason } });
  const path = a.stationId ? `${BASE}/tickets/${a.ticketId}/stations/${a.stationId}/advance` : `${BASE}/tickets/${a.ticketId}/advance`;
  return client<BumpResult>(path, { body: { from: a.from, actionId: a.actionId } });
}

// ---------------------------------------------------------------- sound and screen

let audio: AudioContext | null = null;

function unlockAudio() {
  try { audio ??= new AudioContext(); void audio.resume(); } catch { audio = null; }
}

/** Two loud tones: easy to hear over a busy kitchen. */
function beep() {
  if (!audio) return;
  try {
    const t = audio.currentTime;
    for (const [at, freq] of [[0, 880], [0.25, 1175]] as const) {
      const osc = audio.createOscillator();
      const gain = audio.createGain();
      osc.frequency.value = freq;
      gain.gain.setValueAtTime(0.0001, t + at);
      gain.gain.exponentialRampToValueAtTime(0.6, t + at + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, t + at + 0.2);
      osc.connect(gain).connect(audio.destination);
      osc.start(t + at); osc.stop(t + at + 0.22);
    }
  } catch { /* no sound available */ }
}

/** Stop the tablet from sleeping while the kitchen is open. */
async function keepAwake() {
  try {
    const nav = navigator as Navigator & { wakeLock?: { request(type: "screen"): Promise<unknown> } };
    await nav.wakeLock?.request("screen");
  } catch { /* not supported or refused: the device's own sleep setting applies */ }
}
