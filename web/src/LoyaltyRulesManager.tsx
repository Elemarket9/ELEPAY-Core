import { useEffect, useState } from "react";
import { api } from "./api";
interface Rules { merchantId: string; pointsPer1000Pesewas: number; pesewasPerRedeemedPoint: number; enabled: boolean }
export function LoyaltyRulesManager({ merchantId }: { merchantId: string }) {
  const [rules, setRules] = useState<Rules | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    void api<Rules>("/v1/customers/loyalty/merchant/" + merchantId + "/rules")
      .then(setRules).catch((e) => setError(String(e)));
  }, [merchantId]);
  async function save() {
    if (!rules) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const saved = await api<Rules>("/v1/customers/loyalty/merchant/" + merchantId + "/rules",
        { body: rules });
      setRules(saved); setNotice("Merchant loyalty rules saved.");
    } catch (e) { setError(e instanceof Error ? e.message : "Could not update rules"); }
    finally { setBusy(false); }
  }
  return <section className="panel loyalty-rules">
    <h3>Merchant loyalty program</h3>
    <p className="muted">Controls future loyalty awards and redemptions; no stored money, customer wallet or fake purchases.</p>
    {error && <p className="error">{error}</p>}
    {notice && <p className="notice ok">{notice}</p>}
    {rules && <div className="grid-2">
      <label>Points per GH₵10 spent<input type="number" min={0} max={1000}
        value={rules.pointsPer1000Pesewas} onChange={(e) =>
          setRules({ ...rules, pointsPer1000Pesewas: Number(e.target.value) })}/></label>
      <label>Value of each redeemed point (pesewas)<input type="number" min={0} max={100000}
        value={rules.pesewasPerRedeemedPoint} onChange={(e) =>
          setRules({ ...rules, pesewasPerRedeemedPoint: Number(e.target.value) })}/></label>
      <label><input type="checkbox" checked={rules.enabled} onChange={(e) =>
        setRules({ ...rules, enabled: e.target.checked })}/> Program enabled</label>
      <button disabled={busy} onClick={() => void save()}>Save loyalty rules</button>
    </div>}
  </section>;
}
