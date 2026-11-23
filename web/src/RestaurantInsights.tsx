import { useEffect, useState } from "react";
import { api } from "./api";
import { getToken } from "./auth";
import { formatMoney } from "./money";
interface Dish { name: string; quantity: number; grossPesewas: number }
interface Staff { identity: string; orders: number; grossPesewas: number }
interface Branch { id: string | null; orders: number; grossPesewas: number }
interface LowStock { itemName: string; sku: string; available: number; reorderPoint: number }
interface Report {
  from: string; through: string; branchId: string | null;
  paidOrders: number; openOrders: number; grossPesewas: number;
  discountPesewas: number; tipsPesewas: number; averagePrepMinutes: number;
  bestDishes: Dish[]; leastSoldDishes: Dish[]; staff: Staff[];
  branches: Branch[]; stockAlerts: LowStock[];
}
const dateToday = () => new Date().toISOString().slice(0, 10);
const dateMonthAgo = () => new Date(Date.now() - 29 * 86400000).toISOString().slice(0, 10);
function Table({ heading, lines, columns }: { heading: string; lines: (string | number)[][]; columns: string[] }) {
  return <section className="panel"><h3>{heading}</h3>
    {lines.length === 0 ? <p className="muted">No recorded activity in this period</p>
      : <table className="grid"><thead><tr>{columns.map((label) => <th key={label}>{label}</th>)}</tr></thead>
        <tbody>{lines.map((line, i) => <tr key={i}>{line.map((cell, j) => <td key={j}>{cell}</td>)}</tr>)}</tbody>
      </table>}</section>;
}
export function RestaurantInsights({ merchantId }: { merchantId: string }) {
  const [from, setFrom] = useState(dateMonthAgo);
  const [through, setThrough] = useState(dateToday);
  const [branchId, setBranchId] = useState("");
  const [report, setReport] = useState<Report | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const path = "/v1/restaurant/insights/" + merchantId +
    "?from=" + from + "&through=" + through + (branchId ? "&branchId=" + branchId : "");
  useEffect(() => {
    let active = true;
    void api<Report>(path).then((data) => {
      if (active) { setReport(data); setError(""); }
    }).catch((e) => { if (active) setError(String(e)); });
    return () => { active = false; };
  }, [path]);

  async function exportCsv() {
    setBusy(true); setError("");
    try {
      const token = await getToken();
      const response = await fetch(path.replace("/insights/" + merchantId + "?",
        "/insights/" + merchantId + "/csv?"), {
          headers: { Accept: "text/csv", ...(token ? { Authorization: "Bearer " + token } : {}) },
        });
      if (!response.ok) throw new Error("Could not export CSV: " + response.status);
      const href = URL.createObjectURL(await response.blob());
      const a = document.createElement("a");
      a.href = href; a.download = "elepay-pos-orders-" + from + "-" + through + ".csv";
      a.click(); URL.revokeObjectURL(href);
    } catch (e) { setError(e instanceof Error ? e.message : "Export failed"); }
    finally { setBusy(false); }
  }
  return <article className="restaurant-insights">
    <h2>Restaurant operations and sales</h2>
    <p className="muted">Actual recorded POS activity in Ghana cedis. Gross POS sales are not settled bank funds or tax filings.</p>
    <div className="grid-2">
      <label>From<input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
      <label>Through<input type="date" value={through} onChange={(e) => setThrough(e.target.value)} /></label>
      <label>Branch<select value={branchId} onChange={(e) => setBranchId(e.target.value)}>
        <option value="">All authorized branches</option>
        {report?.branches.filter((b) => b.id).map((b) =>
          <option key={b.id!} value={b.id!}>{b.id!.slice(0, 8)}</option>)}
      </select></label>
    </div>
    <div className="flex-row"><button onClick={() => void exportCsv()} disabled={busy}>Export CSV</button>
      <button className="secondary" onClick={() => window.print()}>Print / Save PDF</button></div>
    {error && <p role="alert" className="error">{error}</p>}
    {report && <>
      <div className="insights-kpis">
        <div><strong>{formatMoney(report.grossPesewas)}</strong><span>Paid order gross sales</span></div>
        <div><strong>{report.paidOrders}</strong><span>Paid POS orders</span></div>
        <div><strong>{report.openOrders}</strong><span>Open bills</span></div>
        <div><strong>{report.averagePrepMinutes.toFixed(1)} min</strong><span>Mean kitchen ready time</span></div>
        <div><strong>{formatMoney(report.discountPesewas)}</strong><span>Discounts on paid orders</span></div>
        <div><strong>{formatMoney(report.tipsPesewas)}</strong><span>Tips on paid orders</span></div>
      </div>
      <div className="insights-grid">
        <Table heading="Best-selling dishes" columns={["Dish", "Quantity", "Gross"]}
          lines={report.bestDishes.map((d) => [d.name, d.quantity, formatMoney(d.grossPesewas)])}/>
        <Table heading="Least-selling dishes" columns={["Dish", "Quantity", "Gross"]}
          lines={report.leastSoldDishes.map((d) => [d.name, d.quantity, formatMoney(d.grossPesewas)])}/>
        <Table heading="Staff activity" columns={["Staff", "Orders", "Gross"]}
          lines={report.staff.map((d) => [d.identity, d.orders, formatMoney(d.grossPesewas)])}/>
        <Table heading="Branch performance" columns={["Branch", "Orders", "Gross"]}
          lines={report.branches.map((d) => [d.id ?? "Default", d.orders, formatMoney(d.grossPesewas)])}/>
        <Table heading="Stock alerts" columns={["Item", "SKU", "Available", "Reorder point"]}
          lines={report.stockAlerts.map((d) => [d.itemName, d.sku, d.available, d.reorderPoint])}/>
      </div>
    </>}
  </article>;
}
