import { useEffect, useMemo, useState } from "react";
import { api } from "./api";
import { formatMoney } from "./money";
interface MenuItem { variantId: string; name: string; sku: string; pricePesewas: number }
interface Offer { merchantId: string; tableId: string; tableNumber: string; merchantName: string; menu: MenuItem[] }
interface Request { id: string; status: string; serverOrderId: string | null }
interface CartLine { item: MenuItem; quantity: number; notes: string }
export function TableQrOrder({ token }: { token: string }) {
  const [offer, setOffer] = useState<Offer | null>(null);
  const [cart, setCart] = useState<CartLine[]>([]);
  const [request, setRequest] = useState<Request | null>(null);
  const [clientKey] = useState(() => crypto.randomUUID());
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let active = true;
    void api<Offer>("/v1/public/restaurant/table-qr/" + encodeURIComponent(token), { anonymous: true })
      .then((data) => { if (active) setOffer(data); })
      .catch((e) => { if (active) setError(e instanceof Error ? e.message : "QR menu unavailable"); });
    return () => { active = false; };
  }, [token]);
  useEffect(() => {
    if (!request || request.status !== "PENDING") return;
    const timer = window.setInterval(() => {
      void api<Request>("/v1/public/restaurant/table-qr/requests/" +
        encodeURIComponent(request.id) + "/status", { anonymous: true })
        .then(setRequest).catch(() => {});
    }, 5000);
    return () => clearInterval(timer);
  }, [request?.id, request?.status]);
  const total = useMemo(() => cart.reduce((sum, row) =>
    sum + row.quantity * row.item.pricePesewas, 0), [cart]);
  function add(item: MenuItem) {
    setCart((rows) => rows.some((x) => x.item.variantId === item.variantId)
      ? rows.map((x) => x.item.variantId === item.variantId
        ? { ...x, quantity: Math.min(10, x.quantity + 1) } : x)
      : [...rows, { item, quantity: 1, notes: "" }]);
  }
  function quantity(id: string, n: number) {
    setCart((rows) => n <= 0 ? rows.filter((x) => x.item.variantId !== id)
      : rows.map((x) => x.item.variantId === id
        ? { ...x, quantity: Math.min(10, n) } : x));
  }
  async function submit() {
    if (busy || !cart.length) return;
    setBusy(true); setError("");
    try {
      const next = await api<Request>("/v1/public/restaurant/table-qr/" +
        encodeURIComponent(token) + "/orders", { anonymous: true,
        body: { clientKey, items: cart.map((x) => ({
          variantId: x.item.variantId, quantity: x.quantity, notes: x.notes,
        })) },
      });
      setRequest(next);
    } catch (e) { setError(e instanceof Error ? e.message : "Submission failed"); }
    finally { setBusy(false); }
  }
  return <main className="table-qr-page">
    <h1>{offer?.merchantName ?? "Restaurant menu"}</h1>
    {offer && <p>Table <strong>{offer.tableNumber}</strong></p>}
    <p>Restaurant staff approve your request before cooking. You have not been charged.</p>
    {error && <p role="alert" className="error">{error}</p>}
    {request ? <section className="panel">
      <h2>{request.status === "PENDING" ? "Waiting for restaurant approval" :
        request.status === "ACCEPTED" ? "Approved — sent to kitchen" : "Request not accepted"}</h2>
      <p>Request reference: {request.id.slice(0, 8)}</p>
      <p>No card or MoMo charge was made through this page.</p>
    </section> : <>
      <div className="table-menu">{offer?.menu.map((item) => <button className="secondary"
        key={item.variantId} onClick={() => add(item)}>
        <strong>{item.name}</strong><span>{formatMoney(item.pricePesewas)}</span><span>+ Add</span>
      </button>)}</div>
      <section className="panel">
        <h2>Your selection</h2>
        {cart.length === 0 && <p>Select dishes from the menu.</p>}
        {cart.map((x) => <div className="table-cart-row" key={x.item.variantId}>
          <strong>{x.item.name}</strong><span>{formatMoney(x.quantity * x.item.pricePesewas)}</span>
          <button className="secondary" onClick={() => quantity(x.item.variantId, x.quantity - 1)}>−</button>
          <span>{x.quantity}</span>
          <button className="secondary" onClick={() => quantity(x.item.variantId, x.quantity + 1)}>+</button>
          <input value={x.notes} maxLength={200} placeholder="Kitchen note (optional)"
            aria-label={"Notes for " + x.item.name}
            onChange={(e) => setCart((rows) => rows.map((row) =>
              row.item.variantId === x.item.variantId ? { ...row, notes: e.target.value } : row))}/>
        </div>)}
        <p className="table-qr-total">Estimated total: {formatMoney(total)}</p>
        <button disabled={!cart.length || busy} onClick={() => void submit()}>
          Send request to restaurant staff
        </button>
      </section>
    </>}
  </main>;
}
