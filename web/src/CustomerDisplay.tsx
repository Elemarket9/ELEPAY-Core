import { useEffect, useState } from "react";
import { formatMoney } from "./money";
interface SaleMirror {
  kind: "sale"; merchantName: string; items: { label: string; quantity: number; totalPesewas: number }[];
  totalPesewas: number; amountPaidPesewas: number; orderStatus: string; updatedAt: number;
}
/** Same-origin second display only; no external customer identity or card information. */
export function CustomerDisplay() {
  const merchantId = new URLSearchParams(location.search).get("merchantId") ?? "";
  const [sale, setSale] = useState<SaleMirror | null>(null);
  const [stale, setStale] = useState(false);
  useEffect(() => {
    if (!/^[a-f\d-]{36}$/i.test(merchantId)) return;
    const stream = new BroadcastChannel("elepay-pos-display:" + merchantId);
    stream.onmessage = (event: MessageEvent<SaleMirror>) => {
      if (event.data?.kind !== "sale" || !Array.isArray(event.data.items)) return;
      setSale(event.data); setStale(false);
    };
    stream.postMessage({ kind: "request-current" });
    const interval = window.setInterval(() => setStale((prev) => prev ||
      (!!sale && Date.now() - sale.updatedAt > 30000)), 5000);
    return () => { clearInterval(interval); stream.close(); };
  }, [merchantId, sale?.updatedAt]);
  return (
    <main className="customer-display">
      <h1>{sale?.merchantName ?? "ELEPAY Customer Display"}</h1>
      {!merchantId && <p>Open this screen from an authenticated merchant POS register.</p>}
      {stale && <p role="alert">Cashier display not updating. Confirm the sale with staff.</p>}
      {!sale ? <p>Waiting for cashier to start a sale on this browser.</p> :
        <>
          <ul>{sale.items.map((i, n) => <li key={n}>
            <span>{i.quantity}× {i.label}</span><strong>{formatMoney(i.totalPesewas)}</strong>
          </li>)}</ul>
          <p className="customer-total">Total: {formatMoney(sale.totalPesewas)}</p>
          <p>Received: {formatMoney(sale.amountPaidPesewas)}</p>
          <p>{sale.orderStatus === "LOCAL_UNRECONCILED"
            ? "Cash noted locally — not synchronized or electronically verified"
            : sale.orderStatus === "PAID" ? "Payment confirmed by ELEPAY" : "Awaiting payment"}</p>
        </>}
    </main>
  );
}
