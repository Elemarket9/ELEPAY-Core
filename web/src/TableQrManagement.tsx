import { useEffect, useState } from "react";
import QRCode from "qrcode";
import { api } from "./api";
import { formatMoney } from "./money";
interface Table { id: string; tableNumber: string; status: string }
interface Rotated { tableId: string; tableNumber: string; relativeUrl: string }
interface Request {
  id: string; tableId: string; tableNumber: string;
  createdAt: string; expiresAt: string; totalPesewas: number;
  lines: { name: string; quantity: number; notes: string | null }[];
}
export function TableQrManagement({ merchantId }: { merchantId: string }) {
  const [tables, setTables] = useState<Table[]>([]);
  const [pending, setPending] = useState<Request[]>([]);
  const [printed, setPrinted] = useState<{ qr: Rotated; image: string } | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function refresh() {
    const [t, p] = await Promise.all([
      api<Table[]>("/v1/restaurant/tables/merchant/" + merchantId),
      api<Request[]>("/v1/restaurant/table-qr/merchant/" + merchantId + "/requests"),
    ]);
    setTables(t); setPending(p);
  }
  useEffect(() => { void refresh().catch((e) => setError(String(e))); }, [merchantId]);
  async function rotate(tableId: string) {
    setBusy(true); setError("");
    try {
      const qr = await api<Rotated>("/v1/restaurant/table-qr/tables/" + tableId + "/rotate",
        { method: "POST" });
      const image = await QRCode.toDataURL(location.origin + qr.relativeUrl, { width: 300, margin: 2 });
      setPrinted({ qr, image });
    } catch (e) { setError(String(e)); } finally { setBusy(false); }
  }
  async function decide(id: string, accepted: boolean) {
    setBusy(true); setError("");
    try {
      await api("/v1/restaurant/table-qr/requests/" + id + (accepted ? "/accept" : "/reject"),
        { method: "POST" });
      await refresh();
    } catch (e) { setError(String(e)); } finally { setBusy(false); }
  }
  return <section className="table-qr-manager">
    <h2>Restaurant table QR requests</h2>
    <p>Rotate a table token and print it; old QR tokens stop working. A request never starts cooking without approval.</p>
    {error && <p role="alert" className="error">{error}</p>}
    <div className="table-qr-controls">{tables.map((t) => <div key={t.id} className="panel">
      <h3>Table {t.tableNumber}</h3>
      <button disabled={busy} onClick={() => void rotate(t.id)}>Create / rotate QR</button>
      <button className="secondary" disabled={busy} onClick={() => {
        void api("/v1/restaurant/table-qr/tables/" + t.id + "/disable", { method: "POST" })
          .then(() => setPrinted(null)).catch((e) => setError(String(e)));
      }}>Disable QR</button>
    </div>)}</div>
    {printed && <div className="panel qr-to-print">
      <h3>Print for table {printed.qr.tableNumber}</h3>
      <img src={printed.image} alt={"Table QR " + printed.qr.tableNumber} width={300} height={300}/>
      <p>Scanning places an approval request, not a financial transaction.</p>
      <button onClick={() => window.print()}>Print table QR</button>
    </div>}
    <h2>Pending orders requiring approval</h2>
    <button className="secondary" disabled={busy} onClick={() =>
      void refresh().catch((e) => setError(String(e)))}>Refresh requests</button>
    {pending.length === 0 && <p>No requests awaiting approval.</p>}
    <div className="table-qr-controls">{pending.map((r) => <div className="panel" key={r.id}>
      <h3>Table {r.tableNumber}</h3>
      <ul>{r.lines.map((x, i) => <li key={i}>{x.quantity}× {x.name} {x.notes ?? ""}</li>)}</ul>
      <p><strong>{formatMoney(r.totalPesewas)}</strong> · {new Date(r.createdAt).toLocaleTimeString()}</p>
      <button disabled={busy} onClick={() => void decide(r.id, true)}>Approve and send to kitchen</button>
      <button className="secondary" disabled={busy} onClick={() => void decide(r.id, false)}>Reject</button>
    </div>)}</div>
  </section>;
}
