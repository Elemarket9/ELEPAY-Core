import { useCallback, useEffect, useMemo, useState } from "react";
import { api, ApiError, newKey } from "../api";

export interface FloorPlan {
  id: string;
  name: string;
}

export type TableStatus = "AVAILABLE" | "OCCUPIED" | "BILL_PRINTED" | "RESERVED" | "DIRTY";

export interface DiningTable {
  id: string;
  sectionId: string;
  tableNumber: string;
  capacity: number;
  status: TableStatus;
  currentOrderId?: string | null;
  branchId?: string | null;
}

export interface FloorPlanViewProps {
  merchantId: string;
  branchId?: string | null;
}

type TableActionDialog =
  | { kind: "move"; table: DiningTable }
  | { kind: "merge"; table: DiningTable };

function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return e.message || `Request failed (${e.status})`;
  return e instanceof Error ? e.message : String(e);
}

function compatibleBranch(a: DiningTable, b: DiningTable): boolean {
  if (a.branchId && b.branchId) return a.branchId === b.branchId;
  return true;
}

function statusTagClass(status: TableStatus): string {
  switch (status) {
    case "AVAILABLE":
      return "active";
    case "OCCUPIED":
      return "pending";
    case "BILL_PRINTED":
      return "bill-printed";
    case "RESERVED":
      return "info";
    case "DIRTY":
      return "failed";
  }
}

function statusLabel(status: TableStatus): string {
  switch (status) {
    case "DIRTY":
      return "Clean";
    case "BILL_PRINTED":
      return "Bill printed";
    default:
      return status.toLowerCase();
  }
}

export function FloorPlanView({ merchantId, branchId }: FloorPlanViewProps) {
  const [plans, setPlans] = useState<FloorPlan[]>([]);
  const [tables, setTables] = useState<DiningTable[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [dialog, setDialog] = useState<TableActionDialog | null>(null);
  const [selectedTargetId, setSelectedTargetId] = useState<string>("");
  const [section, setSection] = useState("");
  const [table, setTable] = useState({ number: "", capacity: "4", sectionId: "" });

  const tablesPath = useMemo(() => {
    const base = `/v1/restaurant/tables/merchant/${merchantId}`;
    return branchId ? `${base}?branchId=${encodeURIComponent(branchId)}` : base;
  }, [merchantId, branchId]);

  const reloadPlans = useCallback(async () => {
    try {
      const res = await api<FloorPlan[]>(`/v1/restaurant/floor-plans/merchant/${merchantId}`);
      setPlans(res);
      setLoadError(null);
    } catch (err) {
      setLoadError(errorMessage(err));
    }
  }, [merchantId]);

  const reloadTables = useCallback(async () => {
    try {
      const res = await api<DiningTable[]>(tablesPath);
      setTables(res);
      setLoadError(null);
    } catch (err) {
      setLoadError(errorMessage(err));
    }
  }, [tablesPath]);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      api<FloorPlan[]>(`/v1/restaurant/floor-plans/merchant/${merchantId}`),
      api<DiningTable[]>(tablesPath),
    ])
      .then(([p, t]) => {
        if (!cancelled) {
          setPlans(p);
          setTables(t);
          setLoadError(null);
        }
      })
      .catch((err) => {
        if (!cancelled) setLoadError(errorMessage(err));
      });
    return () => {
      cancelled = true;
    };
  }, [merchantId, tablesPath]);

  const sectionNames = useMemo(() => {
    const map = new Map<string, string>();
    for (const p of plans) map.set(p.id, p.name);
    return map;
  }, [plans]);

  const dialogCandidates = useMemo(() => {
    if (!dialog) return [];
    if (dialog.kind === "move") {
      return tables.filter(
        (other) =>
          other.id !== dialog.table.id &&
          !other.currentOrderId &&
          (other.status === "AVAILABLE" || other.status === "RESERVED") &&
          compatibleBranch(dialog.table, other),
      );
    }
    return tables.filter(
      (other) =>
        other.id !== dialog.table.id &&
        !!other.currentOrderId &&
        other.currentOrderId !== dialog.table.currentOrderId &&
        (other.status === "OCCUPIED" || other.status === "BILL_PRINTED") &&
        compatibleBranch(dialog.table, other),
    );
  }, [dialog, tables]);

  function openDialog(kind: "move" | "merge", t: DiningTable) {
    setError(null);
    setNotice(null);
    setDialog({ kind, table: t });
    setSelectedTargetId("");
  }

  async function addSection(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setNotice(null);
    try {
      await api("/v1/restaurant/floor-plans", {
        body: { merchantId, name: section.trim(), layoutJson: "{}" },
      });
      setSection("");
      await reloadPlans();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function addTable(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setNotice(null);
    const capacity = Number(table.capacity);
    const sectionId = table.sectionId || plans[0]?.id;
    if (!sectionId) {
      setError("Add a section (e.g. Terrace) first");
      return;
    }
    if (!Number.isInteger(capacity) || capacity < 1) {
      setError("Seats must be a whole number");
      return;
    }
    try {
      await api("/v1/restaurant/tables", {
        body: {
          merchantId,
          sectionId,
          tableNumber: table.number.trim(),
          capacity,
          ...(branchId ? { branchId } : {}),
        },
      });
      setTable({ ...table, number: "" });
      await reloadTables();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function setStatus(t: DiningTable, status: TableStatus) {
    setError(null);
    setNotice(null);
    setBusyId(t.id);
    try {
      if (status === "BILL_PRINTED" && t.currentOrderId) {
        await api(`/v1/restaurant/tables/${t.id}/print-bill`, { method: "POST" });
      } else if (status === "AVAILABLE" && t.status !== "RESERVED") {
        await api(`/v1/restaurant/tables/${t.id}/vacate?markDirty=false`, { method: "POST" });
      } else {
        await api(`/v1/restaurant/tables/${t.id}/status?status=${status}`, { method: "POST" });
      }
      await reloadTables();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusyId(null);
    }
  }

  async function printBill(t: DiningTable) {
    setError(null);
    setNotice(null);
    setBusyId(t.id);
    try {
      await api(`/v1/restaurant/tables/${t.id}/print-bill`, { method: "POST" });
      setNotice(`Bill marked printed for ${t.tableNumber}.`);
      await reloadTables();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusyId(null);
    }
  }

  async function confirmDialogAction(e: React.FormEvent) {
    e.preventDefault();
    if (!dialog) return;
    const chosenId = selectedTargetId || dialogCandidates[0]?.id;
    const chosen = dialogCandidates.find((c) => c.id === chosenId);
    if (!chosen) {
      setError(dialog.kind === "move" ? "Select a destination table" : "Select a table to merge");
      return;
    }
    setError(null);
    setNotice(null);
    setBusyId(dialog.table.id);
    try {
      if (dialog.kind === "move") {
        await api(
          `/v1/restaurant/tables/${dialog.table.id}/move?to=${encodeURIComponent(chosen.id)}`,
          { method: "POST", idempotencyKey: newKey() },
        );
        setNotice(`Moved ${dialog.table.tableNumber} to ${chosen.tableNumber}.`);
      } else {
        await api(
          `/v1/restaurant/tables/${dialog.table.id}/merge?source=${encodeURIComponent(chosen.id)}`,
          { method: "POST" },
        );
        setNotice(`Merged ${chosen.tableNumber} into ${dialog.table.tableNumber}.`);
      }
      setDialog(null);
      setSelectedTargetId("");
      await reloadTables();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusyId(null);
    }
  }

  const statusCounts = {
    AVAILABLE: tables.filter((t) => t.status === "AVAILABLE").length,
    OCCUPIED: tables.filter((t) => t.status === "OCCUPIED").length,
    BILL_PRINTED: tables.filter((t) => t.status === "BILL_PRINTED").length,
    RESERVED: tables.filter((t) => t.status === "RESERVED").length,
    DIRTY: tables.filter((t) => t.status === "DIRTY").length,
  };

  return (
    <div>
      {(loadError || error) && <p className="notice error">{loadError ?? error}</p>}
      {notice && <p className="notice ok">{notice}</p>}

      {tables.length > 0 && (
        <div className="floor-summary">
          <span className="floor-stat-pill">
            <span className="tag active">{statusCounts.AVAILABLE}</span> Available
          </span>
          <span className="floor-stat-pill">
            <span className="tag pending">{statusCounts.OCCUPIED}</span> Occupied
          </span>
          <span className="floor-stat-pill">
            <span className="tag bill-printed">{statusCounts.BILL_PRINTED}</span> Bill printed
          </span>
          <span className="floor-stat-pill">
            <span className="tag info">{statusCounts.RESERVED}</span> Reserved
          </span>
          <span className="floor-stat-pill">
            <span className="tag failed">{statusCounts.DIRTY}</span> Needs cleaning
          </span>
        </div>
      )}

      {plans.length === 0 && (
        <div className="panel" style={{ marginBottom: "1rem" }}>
          <p className="muted" style={{ margin: 0 }}>
            No floor plan yet. Add your sections (e.g. Inside, Terrace) and then add your tables below.
          </p>
        </div>
      )}

      {plans.map((p) => {
        const mine = tables.filter((t) => t.sectionId === p.id);
        return (
          <div key={p.id} className="panel" style={{ marginBottom: "1rem" }}>
            <div className="flex-between" style={{ marginBottom: "0.75rem" }}>
              <h4 style={{ margin: 0 }}>{p.name}</h4>
              <span className="small muted">
                {mine.length} table{mine.length === 1 ? "" : "s"}
              </span>
            </div>
            {mine.length === 0 ? (
              <p className="muted small" style={{ margin: 0 }}>
                No tables in this section yet.
              </p>
            ) : (
              <div className="table-grid">
                {mine.map((t) => {
                  const hasActiveOrder =
                    !!t.currentOrderId && (t.status === "OCCUPIED" || t.status === "BILL_PRINTED");
                  const mergedPeers = t.currentOrderId
                    ? tables.filter((o) => o.id !== t.id && o.currentOrderId === t.currentOrderId)
                    : [];
                  const isBusy = busyId === t.id;
                  return (
                    <div key={t.id} className={`dining-table ${t.status.toLowerCase()}`}>
                      <div className="flex-between">
                        <strong style={{ fontSize: "1rem" }}>{t.tableNumber}</strong>
                        <span className={`tag ${statusTagClass(t.status)}`}>{statusLabel(t.status)}</span>
                      </div>
                      <span className="small muted">{t.capacity} seats</span>
                      {mergedPeers.length > 0 && (
                        <span className="small dining-table-merged">
                          Merged with {mergedPeers.map((m) => m.tableNumber).join(", ")}
                        </span>
                      )}
                      <select
                        value={t.status}
                        disabled={isBusy}
                        onChange={(e) => void setStatus(t, e.target.value as TableStatus)}
                        aria-label={`Status of ${t.tableNumber}`}
                      >
                        <option value="AVAILABLE">Available</option>
                        <option value="OCCUPIED">Occupied</option>
                        <option value="BILL_PRINTED">Bill printed</option>
                        <option value="RESERVED">Reserved</option>
                        <option value="DIRTY">Needs cleaning</option>
                      </select>
                      {hasActiveOrder && (
                        <div className="dining-table-actions">
                          {t.status === "OCCUPIED" && (
                            <button
                              type="button"
                              className="dining-table-action-btn bill"
                              disabled={isBusy}
                              onClick={() => void printBill(t)}
                            >
                              Print bill
                            </button>
                          )}
                          <button
                            type="button"
                            className="dining-table-action-btn"
                            disabled={isBusy}
                            onClick={() => openDialog("move", t)}
                          >
                            Move
                          </button>
                          <button
                            type="button"
                            className="dining-table-action-btn"
                            disabled={isBusy}
                            onClick={() => openDialog("merge", t)}
                          >
                            Merge
                          </button>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        );
      })}

      {dialog && (
        <div className="floor-modal-backdrop" role="dialog" aria-modal="true" aria-label={dialog.kind === "move" ? "Move table" : "Merge tables"}>
          <form className="panel floor-modal" onSubmit={confirmDialogAction}>
            <div className="flex-between" style={{ marginBottom: "0.5rem" }}>
              <h4 style={{ margin: 0 }}>
                {dialog.kind === "move"
                  ? `Move ${dialog.table.tableNumber}`
                  : `Merge into ${dialog.table.tableNumber}`}
              </h4>
              <button type="button" className="secondary" onClick={() => setDialog(null)}>
                Close
              </button>
            </div>
            <p className="muted small" style={{ marginTop: 0 }}>
              {dialog.kind === "move"
                ? `Move the active order from ${dialog.table.tableNumber} to an available or reserved table. ${dialog.table.tableNumber} will be marked needs cleaning.`
                : `Move all items from the selected table into ${dialog.table.tableNumber}. Both tables will display the merged order until settled.`}
            </p>
            {dialogCandidates.length === 0 ? (
              <p className="muted small">
                {dialog.kind === "move"
                  ? "No available or reserved tables in this branch."
                  : "No other occupied tables available to merge."}
              </p>
            ) : (
              <>
                <label>
                  {dialog.kind === "move" ? "Destination table" : "Source table to merge"}
                  <select
                    value={selectedTargetId || dialogCandidates[0]?.id || ""}
                    onChange={(e) => setSelectedTargetId(e.target.value)}
                  >
                    {dialogCandidates.map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.tableNumber} ({sectionNames.get(c.sectionId) ?? "Floor"} · {c.capacity} seats · {statusLabel(c.status)})
                      </option>
                    ))}
                  </select>
                </label>
                <div className="floor-modal-footer">
                  <button type="button" className="secondary" onClick={() => setDialog(null)}>
                    Cancel
                  </button>
                  <button type="submit" disabled={busyId !== null}>
                    {dialog.kind === "move" ? "Confirm move" : "Confirm merge"}
                  </button>
                </div>
              </>
            )}
          </form>
        </div>
      )}

      <div className="grid-2">
        <form className="panel" onSubmit={addSection}>
          <h4>Add section</h4>
          <label>
            Name
            <input
              value={section}
              onChange={(e) => setSection(e.target.value)}
              placeholder="Inside, Terrace…"
              required
              maxLength={64}
            />
          </label>
          <button type="submit">Add section</button>
        </form>
        <form className="panel" onSubmit={addTable}>
          <h4>Add table</h4>
          <label>
            Table name
            <input
              value={table.number}
              onChange={(e) => setTable({ ...table, number: e.target.value })}
              placeholder="T1"
              required
              maxLength={32}
            />
          </label>
          <label>
            Seats
            <input
              inputMode="numeric"
              value={table.capacity}
              onChange={(e) => setTable({ ...table, capacity: e.target.value })}
              required
            />
          </label>
          {plans.length > 1 && (
            <label>
              Section
              <select
                value={table.sectionId}
                onChange={(e) => setTable({ ...table, sectionId: e.target.value })}
              >
                {plans.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            </label>
          )}
          <button type="submit" disabled={plans.length === 0}>
            Add table
          </button>
        </form>
      </div>
    </div>
  );
}

export default FloorPlanView;
