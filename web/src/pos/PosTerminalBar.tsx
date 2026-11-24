import { useCallback, useEffect, useState } from "react";
import { api, ApiError } from "../api";
import { formatMoney, parseMoney } from "../money";
import { isFeatureNotEnabled, PosCashier, PosShift, PosTerminal } from "./posHelpers";

interface PosTerminalBarProps {
  merchantId: string;
  terminalId: string | null;
  cashierId: string | null;
  onSelectTerminal: (terminal: PosTerminal | null) => void;
  onSelectCashier: (cashier: { id: string; badgeNumber: string; name?: string } | null) => void;
  onModuleDisabled: () => void;
}

export function upsertKnownCashier(list: PosCashier[], cashier: PosCashier): PosCashier[] {
  return [cashier, ...list.filter((c) => c.id !== cashier.id)];
}

export function PosTerminalBar({
  merchantId,
  terminalId,
  cashierId,
  onSelectTerminal,
  onSelectCashier,
  onModuleDisabled,
}: PosTerminalBarProps) {
  const [terminals, setTerminals] = useState<PosTerminal[]>([]);
  const [shift, setShift] = useState<PosShift | null>(null);
  const [cashierBadge, setCashierBadge] = useState("");
  const [cashierPin, setCashierPin] = useState("");
  const [cashierName, setCashierName] = useState("");
  const [authBadgeLabel, setAuthBadgeLabel] = useState<string | null>(null);
  const [panel, setPanel] = useState<"none" | "register-terminal" | "pair-terminal" | "cashier" | "shift">("none");

  const [serialNumber, setSerialNumber] = useState("");
  const [model, setModel] = useState("ELEPAY-T1");
  const [deviceName, setDeviceName] = useState("");
  const [city, setCity] = useState("Accra");
  const [pairingCode, setPairingCode] = useState("");
  const [hardwareFp, setHardwareFp] = useState("");
  const [openingCash, setOpeningCash] = useState("0.00");
  const [closingCash, setClosingCash] = useState("0.00");
  const [shiftNotes, setShiftNotes] = useState("");
  const [knownCashiers, setKnownCashiers] = useState<PosCashier[]>([]);
  const [statusMsg, setStatusMsg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const loadTerminals = useCallback(async () => {
    try {
      const list = await api<PosTerminal[]>(`/v1/pos/terminals/merchant/${merchantId}`);
      setTerminals(list);
      if (!terminalId && list.length > 0) {
        onSelectTerminal(list[0]);
      }
      setError(null);
    } catch (e) {
      if (isFeatureNotEnabled(e)) {
        onModuleDisabled();
        return;
      }
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }, [merchantId, terminalId, onSelectTerminal, onModuleDisabled]);

  const loadShift = useCallback(async (tid: string | null) => {
    if (!tid) {
      setShift(null);
      return;
    }
    try {
      const active = await api<PosShift>(`/v1/pos/shifts/active/${tid}`);
      setShift(active);
    } catch (e) {
      if (isFeatureNotEnabled(e)) {
        onModuleDisabled();
        return;
      }
      if (e instanceof ApiError && e.status === 404) {
        setShift(null);
      }
    }
  }, [onModuleDisabled]);

  useEffect(() => {
    void loadTerminals();
  }, [loadTerminals]);

  useEffect(() => {
    void loadShift(terminalId);
  }, [terminalId, loadShift]);

  async function registerTerminal(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setStatusMsg(null);
    setBusy(true);
    try {
      const created = await api<PosTerminal>("/v1/pos/terminals/register", {
        body: {
          merchantId,
          branchId: null,
          serialNumber: serialNumber.trim(),
          model: model.trim() || "ELEPAY-T1",
          city: city.trim() || "Accra",
          region: "Greater Accra",
          country: "GH",
          address: null,
          location: null,
          latitude: null,
          longitude: null,
          connectionType: "WIFI",
          manufacturer: "ELEPAY",
          manufactureYear: 2026,
          deviceName: deviceName.trim() || serialNumber.trim(),
          deviceDescription: "POS Register Terminal",
        },
      });
      setSerialNumber("");
      setDeviceName("");
      setPanel("none");
      setStatusMsg(`Terminal ${created.serialNumber} registered.`);
      await loadTerminals();
      onSelectTerminal(created);
    } catch (err) {
      if (isFeatureNotEnabled(err)) { onModuleDisabled(); return; }
      setError(err instanceof ApiError ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  async function pairTerminal(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setStatusMsg(null);
    setBusy(true);
    try {
      const res = await api<{ terminalId: string; serialNumber: string; status: string }>("/v1/pos/terminals/pair", {
        body: {
          serialNumber: serialNumber.trim(),
          pairingCode: pairingCode.trim(),
          hardwareFingerprint: hardwareFp.trim() || `web-${navigator.userAgent.slice(0, 32)}`,
        },
      });
      setPairingCode("");
      setPanel("none");
      setStatusMsg(`Terminal ${res.serialNumber} paired (${res.status}).`);
      await loadTerminals();
    } catch (err) {
      if (isFeatureNotEnabled(err)) { onModuleDisabled(); return; }
      setError(err instanceof ApiError ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  async function registerCashier(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setStatusMsg(null);
    setBusy(true);
    try {
      const created = await api<PosCashier>("/v1/pos/cashiers/register", {
        body: {
          merchantId,
          name: cashierName.trim(),
          pin: cashierPin,
          badgeNumber: cashierBadge.trim(),
        },
      });
      setKnownCashiers((prev) => upsertKnownCashier(prev, created));
      onSelectCashier({ id: created.id, badgeNumber: created.badgeNumber, name: created.name });
      setAuthBadgeLabel(`${created.name} (${created.badgeNumber})`);
      setCashierPin("");
      setCashierName("");
      setPanel("none");
      setStatusMsg(`Cashier ${created.name} registered & active.`);
    } catch (err) {
      if (isFeatureNotEnabled(err)) { onModuleDisabled(); return; }
      setError(err instanceof ApiError ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  async function authenticateCashier(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setStatusMsg(null);
    setBusy(true);
    try {
      const cashier = await api<PosCashier>("/v1/pos/cashiers/auth", {
        body: {
          merchantId,
          badgeNumber: cashierBadge.trim(),
          pin: cashierPin,
        },
      });
      const resolved = cashier?.id
        ? cashier
        : knownCashiers.find((c) => c.badgeNumber === cashierBadge.trim()) ?? null;
      if (resolved) {
        setKnownCashiers((prev) => upsertKnownCashier(prev, resolved));
        onSelectCashier({ id: resolved.id, badgeNumber: resolved.badgeNumber, name: resolved.name });
        setAuthBadgeLabel(`${resolved.name} (${resolved.badgeNumber})`);
        setStatusMsg(`Cashier ${resolved.name} (${resolved.badgeNumber}) verified & active.`);
      } else {
        setAuthBadgeLabel(`Badge ${cashierBadge.trim()} (PIN verified)`);
        setStatusMsg(`Cashier PIN verified for badge ${cashierBadge.trim()}.`);
      }
      setCashierPin("");
      setPanel("none");
    } catch (err) {
      if (isFeatureNotEnabled(err)) { onModuleDisabled(); return; }
      setError(err instanceof ApiError && err.status === 401 ? "Invalid cashier badge or PIN." : (err instanceof ApiError ? err.message : String(err)));
    } finally {
      setBusy(false);
    }
  }

  async function openShift(e: React.FormEvent) {
    e.preventDefault();
    if (!terminalId || !cashierId) {
      setError("Select a terminal and authenticate a cashier first.");
      return;
    }
    const pesewas = parseMoney(openingCash);
    if (pesewas === null || pesewas < 0) {
      setError("Enter a valid opening float amount, e.g. 100.00");
      return;
    }
    setError(null);
    setBusy(true);
    try {
      const opened = await api<PosShift>("/v1/pos/shifts/open", {
        body: {
          terminalId,
          cashierId,
          merchantId,
          openingBalancePesewas: pesewas,
          notes: shiftNotes.trim() || null,
        },
      });
      setShift(opened);
      setShiftNotes("");
      setPanel("none");
      setStatusMsg(`Shift opened with ${formatMoney(opened.openingBalancePesewas)} float.`);
    } catch (err) {
      if (isFeatureNotEnabled(err)) { onModuleDisabled(); return; }
      setError(err instanceof ApiError ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  async function closeShift(e: React.FormEvent) {
    e.preventDefault();
    if (!shift) return;
    const pesewas = parseMoney(closingCash);
    if (pesewas === null || pesewas < 0) {
      setError("Enter a valid closing cash count, e.g. 250.00");
      return;
    }
    setError(null);
    setBusy(true);
    try {
      const closed = await api<PosShift>(`/v1/pos/shifts/${shift.id}/close`, {
        body: {
          closingBalancePesewas: pesewas,
          notes: shiftNotes.trim() || null,
        },
      });
      setShift(null);
      setShiftNotes("");
      setPanel("none");
      setStatusMsg(
        `Shift closed. Counted ${formatMoney(closed.closingBalancePesewas ?? pesewas)}, discrepancy ${formatMoney(closed.cashDiscrepancyPesewas ?? 0)}.`
      );
    } catch (err) {
      if (isFeatureNotEnabled(err)) { onModuleDisabled(); return; }
      setError(err instanceof ApiError ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="panel" style={{ marginBottom: "1rem" }}>
      <div className="flex-between" style={{ flexWrap: "wrap", gap: ".5rem" }}>
        <div className="flex-row" style={{ flexWrap: "wrap", gap: ".5rem", alignItems: "center" }}>
          <label className="small" style={{ margin: 0 }}>
            Terminal:
            <select
              value={terminalId ?? ""}
              onChange={(e) => {
                const found = terminals.find((t) => t.id === e.target.value) ?? null;
                onSelectTerminal(found);
              }}
              style={{ marginLeft: ".4rem", width: "auto" }}
            >
              <option value="">-- Select terminal --</option>
              {terminals.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.serialNumber} ({t.model} · {t.status})
                </option>
              ))}
            </select>
          </label>
          <span className="small muted">
            Cashier: <strong>{authBadgeLabel ?? (cashierId ? cashierId.slice(0, 8) : "Not signed in")}</strong>
          </span>
          <span className={`tag ${shift ? "active" : "pending"}`}>
            {shift ? `Shift OPEN (${formatMoney(shift.openingBalancePesewas)})` : "No active shift"}
          </span>
        </div>

        <div className="flex-row" style={{ flexWrap: "wrap", gap: ".4rem" }}>
          <button type="button" className="secondary small" onClick={() => setPanel(panel === "register-terminal" ? "none" : "register-terminal")}>
            + Terminal
          </button>
          <button type="button" className="secondary small" onClick={() => setPanel(panel === "pair-terminal" ? "none" : "pair-terminal")}>
            Pair
          </button>
          <button type="button" className="secondary small" onClick={() => setPanel(panel === "cashier" ? "none" : "cashier")}>
            Cashier PIN
          </button>
          <button type="button" className="secondary small" onClick={() => setPanel(panel === "shift" ? "none" : "shift")}>
            {shift ? "Close Shift" : "Open Shift"}
          </button>
        </div>
      </div>

      {error && <p className="error" style={{ marginTop: ".5rem" }}>{error}</p>}
      {statusMsg && <p className="notice ok" style={{ marginTop: ".5rem" }}>{statusMsg}</p>}

      {panel === "register-terminal" && (
        <form onSubmit={registerTerminal} style={{ marginTop: ".8rem" }}>
          <div className="grid-2">
            <label>Serial Number<input value={serialNumber} onChange={(e) => setSerialNumber(e.target.value)} required placeholder="SN-ACCRA-001" /></label>
            <label>Model<input value={model} onChange={(e) => setModel(e.target.value)} required /></label>
            <label>Device Name<input value={deviceName} onChange={(e) => setDeviceName(e.target.value)} placeholder="Front Counter 1" /></label>
            <label>City<input value={city} onChange={(e) => setCity(e.target.value)} /></label>
          </div>
          <button type="submit" disabled={busy}>{busy ? "Registering…" : "Register Terminal"}</button>
        </form>
      )}

      {panel === "pair-terminal" && (
        <form onSubmit={pairTerminal} style={{ marginTop: ".8rem" }}>
          <div className="grid-2">
            <label>Serial Number<input value={serialNumber} onChange={(e) => setSerialNumber(e.target.value)} required /></label>
            <label>Pairing Code<input value={pairingCode} onChange={(e) => setPairingCode(e.target.value)} required /></label>
            <label>Hardware Fingerprint (optional)<input value={hardwareFp} onChange={(e) => setHardwareFp(e.target.value)} /></label>
          </div>
          <button type="submit" disabled={busy}>{busy ? "Pairing…" : "Pair Terminal"}</button>
        </form>
      )}

      {panel === "cashier" && (
        <div style={{ marginTop: ".8rem" }}>
          <form onSubmit={authenticateCashier} className="grid-2">
            <label>Badge Number<input value={cashierBadge} onChange={(e) => setCashierBadge(e.target.value)} required placeholder="B-001" /></label>
            <label>PIN<input type="password" inputMode="numeric" value={cashierPin} onChange={(e) => setCashierPin(e.target.value)} required placeholder="••••" /></label>
            <div className="flex-row">
              <button type="submit" disabled={busy}>{busy ? "Verifying…" : "Verify PIN"}</button>
            </div>
          </form>
          <details style={{ marginTop: ".6rem" }}>
            <summary className="small muted">Register new cashier</summary>
            <form onSubmit={registerCashier} className="grid-2" style={{ marginTop: ".5rem" }}>
              <label>Cashier Name<input value={cashierName} onChange={(e) => setCashierName(e.target.value)} required /></label>
              <label>Badge Number<input value={cashierBadge} onChange={(e) => setCashierBadge(e.target.value)} required /></label>
              <label>PIN (4-6 digits)<input type="password" inputMode="numeric" value={cashierPin} onChange={(e) => setCashierPin(e.target.value)} required minLength={4} /></label>
              <div className="flex-row" style={{ alignItems: "end" }}>
                <button type="submit" className="secondary" disabled={busy}>Register Cashier</button>
              </div>
            </form>
          </details>
        </div>
      )}

      {panel === "shift" && (
        <form onSubmit={shift ? closeShift : openShift} style={{ marginTop: ".8rem" }}>
          <div className="grid-2">
            {shift ? (
              <label>Closing Cash Count (GH₵)<input inputMode="decimal" value={closingCash} onChange={(e) => setClosingCash(e.target.value)} required /></label>
            ) : (
              <label>Opening Float (GH₵)<input inputMode="decimal" value={openingCash} onChange={(e) => setOpeningCash(e.target.value)} required /></label>
            )}
            <label>Notes<input value={shiftNotes} onChange={(e) => setShiftNotes(e.target.value)} placeholder="Optional shift notes" /></label>
          </div>
          <button type="submit" disabled={busy}>
            {busy ? "Saving…" : shift ? "Close Active Shift" : "Open Shift"}
          </button>
        </form>
      )}
    </div>
  );
}
