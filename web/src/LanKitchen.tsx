import { useEffect, useState } from "react";
import { bumpLanOrder, listLanOrders, readLanSettings, saveLanSettings,
  streamLanOrders, type LanSettings, type LanTicket } from "./lan-edge";
import { KDS_LANGUAGES, kdsText, readKdsLocale, type KdsLanguage } from "./kds/i18n";

function langChoice(lang: KdsLanguage, setLang: (s: KdsLanguage) => void) {
  return <label>{kdsText(lang, "language")}<select value={lang} onChange={(e) => {
    const value = e.target.value as KdsLanguage;
    setLang(value);
    try { localStorage.setItem("elepay.kds.lang", value); } catch { /* volatile setting */ }
  }}>{KDS_LANGUAGES.map(([id, name]) => <option key={id} value={id}>{name}</option>)}</select></label>;
}
export function LanKitchen() {
  const [config, setConfig] = useState<LanSettings | null>(readLanSettings);
  const [url, setUrl] = useState(config?.url ?? "");
  const [token, setToken] = useState(config?.token ?? "");
  const [tickets, setTickets] = useState<LanTicket[]>([]);
  const [problem, setProblem] = useState("");
  const [connected, setConnected] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [lang, setLang] = useState<KdsLanguage>(readKdsLocale);
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);
  useEffect(() => {
    if (!config) return;
    let active = true;
    void listLanOrders(config).then((list) => { if (active) {
      setTickets(list); setConnected(true); setProblem("");
    }}).catch((e) => { if (active) { setConnected(false); setProblem(String(e)); } });
    const stop = streamLanOrders(config, (orders) => {
      if (!active) return;
      setTickets(orders); setConnected(true); setProblem("");
    }, (why) => { if (active) { setConnected(false); setProblem(why); } });
    return () => { active = false; stop(); };
  }, [config]);

  async function pair() {
    try {
      const next = saveLanSettings(url, token);
      const orders = await listLanOrders(next);
      setTickets(orders); setConfig(next); setConnected(true); setProblem("");
    } catch (e) { setProblem(e instanceof Error ? e.message : "LAN pairing failed"); }
  }
  async function bump(ticket: LanTicket) {
    if (!config) return;
    setBusy(ticket.offlineTxId);
    try {
      const updated = await bumpLanOrder(config, ticket.offlineTxId, ticket.status);
      setTickets((old) => old.map((item) => item.offlineTxId === updated.offlineTxId ? updated : item));
      setProblem("");
    } catch (e) { setProblem(e instanceof Error ? e.message : "LAN action rejected"); }
    finally { setBusy(null); }
  }
  function announce(ticket: LanTicket) {
    if (!("speechSynthesis" in window)) return;
    const utterance = new SpeechSynthesisUtterance("Order " + ticket.offlineTxId.slice(0, 6) + " is ready");
    utterance.lang = lang === "en" ? "en-GH" : lang;
    speechSynthesis.speak(utterance);
  }
  return (
    <main className="kitchen lan-kitchen" dir={lang === "ar" ? "rtl" : "ltr"}>
      <div className="kitchen-bar">
        <strong>ELEPAY · {kdsText(lang, "local")} {kdsText(lang, "kitchen")}</strong>
        <span className={connected ? "kitchen-net on" : "kitchen-net off"}>
          {connected ? "LAN connected" : kdsText(lang, "offline")}
        </span>
        <a href="/kitchen">Cloud KDS</a>
      </div>
      <section className="panel lan-pairing">
        {langChoice(lang, setLang)}
        <label>LAN HTTPS URL<input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://kitchen.local:8443" /></label>
        <label>Pairing secret<input type="password" autoComplete="off" value={token} onChange={(e) => setToken(e.target.value)} /></label>
        <button onClick={() => void pair()}>{kdsText(lang, "reconnect")} / Pair</button>
        <p className="small">Only connect to your restaurant's trusted HTTPS local edge. No bank-card authorization occurs here.</p>
        {problem && <p className="error" role="alert">{problem}</p>}
      </section>
      <p>{tickets.filter((v) => v.status !== "SERVED").length} {kdsText(lang, "orders")}</p>
      <div className="kitchen-grid">
        {tickets.filter((t) => t.status !== "SERVED").map((t) => {
          const elapsedMinutes = Math.floor(Math.max(0, now - Date.parse(t.createdAt)) / 60000);
          const next = t.status === "NEW" ? "PREPARING" : t.status === "PREPARING" ? "READY" : "SERVED";
          return <article key={t.offlineTxId} className={
            "kitchen-card " + (elapsedMinutes >= 15 ? "critical" : elapsedMinutes >= 10 ? "warning" : "normal")}>
            <header><strong>#{t.offlineTxId.slice(0, 8)}</strong>
              <span>{t.tableLabel ?? t.orderType.replace("_", " ")}</span>
              <span>{elapsedMinutes} min</span></header>
            <p>{kdsText(lang, "pending")} · {t.status} · Local cash NOT bank-verified</p>
            <ul>{t.items.map((i, n) => <li key={n}>{i.quantity}× {i.itemName} {i.notes ?? ""}</li>)}</ul>
            <button className="kitchen-bump" disabled={!connected || busy === t.offlineTxId}
              onClick={() => void bump(t)}>
              {kdsText(lang, next === "PREPARING" ? "start" : next === "READY" ? "ready" : "served")}
            </button>
            {t.status === "READY" && <button className="secondary"
              onClick={() => announce(t)}>Speak ready</button>}
            {t.serverOrderId && <p className="small">Cloud linked: {t.serverOrderId.slice(0, 8)}</p>}
          </article>;
        })}
      </div>
    </main>
  );
}
