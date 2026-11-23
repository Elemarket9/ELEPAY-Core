import { useEffect, useState } from "react";
import { bumpLanOrder, readLanSettings, saveLanSettings, streamLanOrders,
  type LanSettings, type LanTicket } from "./lan-edge";

/**
 * Separate, clearly provisional LAN Expo queue. No NFC/card settlement or
 * pretending that a local cash collection is a cloud-confirmed payment.
 */
export function LanKitchenBoard({ merchantId, now }: { merchantId: string; now: number }) {
  const [settings, setSettings] = useState<LanSettings | null>(readLanSettings);
  const [url, setUrl] = useState(() => readLanSettings()?.url ?? "");
  const [token, setToken] = useState(() => readLanSettings()?.token ?? "");
  const [orders, setOrders] = useState<LanTicket[]>([]);
  const [problem, setProblem] = useState("");

  useEffect(() => {
    if (!settings) return;
    return streamLanOrders(settings, (incoming) => {
      setOrders(incoming.filter((o) => o.merchantId === merchantId));
      setProblem("");
    }, setProblem);
  }, [settings, merchantId]);

  const open = orders.filter((o) => o.orderType !== "RETAIL" && o.status !== "SERVED");
  const advance = async (order: LanTicket) => {
    if (!settings) return;
    try {
      const next = await bumpLanOrder(settings, order.offlineTxId, order.status);
      setOrders((rows) => rows.map((r) => r.offlineTxId === next.offlineTxId ? next : r));
    } catch (error) {
      setProblem(error instanceof Error ? error.message : "LAN kitchen update failed");
    }
  };

  return (
    <section className="kitchen-lan">
      <h2>Local Wi-Fi kitchen fallback · provisional</h2>
      <p>All stations can see these local orders. Do not prepare a duplicate when
        the cloud KDS reconnects. Check cloud service status before clearing local tickets.</p>
      {!settings && <div className="kitchen-lan-pair">
        <label>LAN edge HTTPS address
          <input value={url} onChange={(e) => setUrl(e.target.value)}
            placeholder="https://kitchen.local:8443" />
        </label>
        <label>LAN pairing secret
          <input type="password" value={token} autoComplete="off"
            onChange={(e) => setToken(e.target.value)} />
        </label>
        <button onClick={() => {
          try { setSettings(saveLanSettings(url, token)); }
          catch (e) { setProblem(e instanceof Error ? e.message : "Invalid LAN pairing"); }
        }}>Connect local kitchen</button>
      </div>}
      {settings && <p className="muted">Local edge {settings.url} · {open.length} provisional orders</p>}
      {problem && <p className="kitchen-alert">{problem}</p>}
      <div className="kitchen-grid">
        {open.map((order) => {
          const minutes = Math.max(0, Math.floor((now - Date.parse(order.createdAt)) / 60000));
          const next = order.status === "NEW" ? "PREPARING" : order.status === "PREPARING" ? "READY" : "SERVED";
          return (
            <article key={order.offlineTxId}
              className={`kitchen-card ${minutes >= 15 ? "critical" : minutes >= 10 ? "warning" : "normal"}`}>
              <header>
                <span className="kitchen-num">LOCAL {order.offlineTxId.slice(0, 6)}</span>
                <span className="kitchen-mode">{order.orderType.replace("_", " ")}</span>
                {order.tableLabel && <span className="kitchen-mode">Table {order.tableLabel}</span>}
                <span className="kitchen-pay">UNVERIFIED</span>
                <span className="kitchen-time">{minutes}m</span>
              </header>
              <ul>
                {order.items.map((item, index) => <li key={index}><b>{item.quantity}×</b> {item.itemName}</li>)}
              </ul>
              {order.serverOrderId && <p className="kitchen-alert">
                Cloud order linked. Confirm service on cloud KDS before continuing.
              </p>}
              <footer>
                <button className="kitchen-bump" disabled={!!order.serverOrderId}
                  onClick={() => void advance(order)}>
                  {next === "PREPARING" ? "Start" : next === "READY" ? "Ready" : "Served"}
                </button>
              </footer>
            </article>
          );
        })}
      </div>
    </section>
  );
}
