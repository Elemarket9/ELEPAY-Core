import { useEffect, useState } from "react";
import { api } from "./api";
import { formatMoney } from "./money";
interface Item { name: string; quantity: number; amountPesewas: number }
interface Receipt {
  merchantName: string; receiptNumber: string; orderNumber: string; createdAt: string;
  subtotalPesewas: number; discountPesewas: number; taxPesewas: number;
  tipPesewas: number; totalPesewas: number; items: Item[];
}
export function DigitalReceipt({ token }: { token: string }) {
  const [receipt, setReceipt] = useState<Receipt | null>(null);
  const [problem, setProblem] = useState("");
  useEffect(() => {
    let active = true;
    void api<Receipt>("/v1/public/pos/receipts/" + encodeURIComponent(token),
      { anonymous: true }).then((data) => { if (active) setReceipt(data); })
      .catch((e) => { if (active) setProblem(e instanceof Error ? e.message : "Receipt not found"); });
    return () => { active = false; };
  }, [token]);
  async function share() {
    const url = location.href;
    try {
      if (navigator.share) await navigator.share({ title: "ELEPAY receipt", url });
      else if (navigator.clipboard) { await navigator.clipboard.writeText(url); }
      else setProblem("Copy the address from the browser to share your receipt.");
    } catch (e) {
      if (e instanceof Error && e.name !== "AbortError") setProblem(e.message);
    }
  }
  return <main className="digital-receipt">
    <h1>{receipt?.merchantName ?? "ELEPAY receipt"}</h1>
    {problem && <p className="error" role="alert">{problem}</p>}
    {receipt && <>
      <h2>Payment receipt</h2>
      <p>Receipt {receipt.receiptNumber} · Order {receipt.orderNumber}</p>
      <p>{new Date(receipt.createdAt).toLocaleString("en-GH", { timeZone: "Africa/Accra" })}</p>
      <table className="grid"><thead><tr><th>Item</th><th>Qty</th><th>Amount</th></tr></thead>
        <tbody>{receipt.items.map((item, i) => <tr key={i}>
          <td>{item.name}</td><td>{item.quantity}</td>
          <td>{formatMoney(item.amountPesewas)}</td></tr>)}</tbody></table>
      <dl className="confirm">
        <dt>Subtotal</dt><dd>{formatMoney(receipt.subtotalPesewas)}</dd>
        <dt>Discount</dt><dd>{formatMoney(receipt.discountPesewas)}</dd>
        <dt>Tax</dt><dd>{formatMoney(receipt.taxPesewas)}</dd>
        <dt>Tips</dt><dd>{formatMoney(receipt.tipPesewas)}</dd>
        <dt>Total on receipt</dt><dd><strong>{formatMoney(receipt.totalPesewas)}</strong></dd>
      </dl>
      <p className="small">This shows a previously issued paid POS receipt, not a live balance or refund status.</p>
      <div className="flex-row"><button onClick={() => void share()}>Share receipt</button>
        <button className="secondary" onClick={() => window.print()}>Print / Save PDF</button></div>
    </>}
  </main>;
}
