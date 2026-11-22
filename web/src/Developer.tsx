import { useEffect, useState } from "react";
import { api, newKey } from "./api";

interface MerchantView { id: string; tradingName: string; kind: string; status: string; merchantCode: string; role: string }

interface ApiKey {
  id: string;
  prefix: string;
  environment: "live" | "test";
  label: string | null;
  scopes: string[];
  createdAt: string;
  revokedAt: string | null;
  lastUsedAt: string | null;
}

interface WebhookEndpoint {
  id: string;
  url: string;
  secret: string;
  environment: "live" | "test";
  description: string | null;
  eventTypes: string[];
  active: boolean;
  failureCount: number;
  lastDeliveryAt: string | null;
  createdAt: string;
}

interface WebhookLog {
  id: string;
  deliveryId: string;
  endpointId: string;
  eventId: string;
  merchantId: string;
  eventType: string;
  requestUrl: string;
  requestHeaders: string;
  requestPayload: string;
  responseCode: number | null;
  responseHeaders: string | null;
  responseBody: string | null;
  durationMs: number;
  status: "PENDING" | "DELIVERED" | "FAILED" | "RETRYING" | "DEAD";
  attemptNumber: number;
  isManualRetry: boolean;
  createdAt: string;
}

const DEV_TABS = ["API Keys", "Webhooks", "Delivery Logs & Replay", "API Explorer", "SDK Snippets", "WooCommerce Plugin"] as const;

export function DeveloperPortal() {
  const [merchants, setMerchants] = useState<MerchantView[]>([]);
  const [currentMerchantId, setCurrentMerchantId] = useState<string | null>(null);
  const [tab, setTab] = useState<(typeof DEV_TABS)[number]>("API Keys");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<MerchantView[]>("/v1/merchants")
      .then((m) => {
        setMerchants(m);
        if (m.length > 0) setCurrentMerchantId(m[0].id);
      })
      .catch((e) => setError(String(e.message ?? e)));
  }, []);

  const merchant = merchants.find((m) => m.id === currentMerchantId);

  return (
    <main>
      <div className="flex-between">
        <div>
          <h1>Developer Platform</h1>
          <p className="muted">API keys, webhooks, interactive explorer, SDK code snippets & WooCommerce gateway.</p>
        </div>
        {merchants.length > 1 && (
          <select value={currentMerchantId ?? ""} onChange={(e) => setCurrentMerchantId(e.target.value)}>
            {merchants.map((m) => (
              <option key={m.id} value={m.id}>{m.tradingName} ({m.merchantCode})</option>
            ))}
          </select>
        )}
      </div>

      {error && <p className="error">{error}</p>}

      {merchant ? (
        <>
          <nav className="tabs">
            {DEV_TABS.map((t) => (
              <button key={t} className={t === tab ? "active" : ""} onClick={() => setTab(t)}>
                {t}
              </button>
            ))}
          </nav>

          {tab === "API Keys" && <ApiKeysView merchantId={merchant.id} />}
          {tab === "Webhooks" && <WebhooksView merchantId={merchant.id} />}
          {tab === "Delivery Logs & Replay" && <DeliveryLogsView merchantId={merchant.id} />}
          {tab === "API Explorer" && <ApiExplorerView merchantId={merchant.id} />}
          {tab === "SDK Snippets" && <SdkSnippetsView />}
          {tab === "WooCommerce Plugin" && <WooCommerceView merchantId={merchant.id} />}
        </>
      ) : (
        <p className="muted">No merchant accounts found. Please register or select an active merchant account.</p>
      )}
    </main>
  );
}

// -------------------------------------------------------------------------------------------------
// 1. API Keys View
// -------------------------------------------------------------------------------------------------
function ApiKeysView({ merchantId }: { merchantId: string }) {
  const [keys, setKeys] = useState<ApiKey[]>([]);
  const [envFilter, setEnvFilter] = useState<string>("all");
  const [msg, setMsg] = useState<string | null>(null);
  const [showModal, setShowModal] = useState(false);
  const [newKeyPlaintext, setNewKeyPlaintext] = useState<string | null>(null);

  // Form state
  const [label, setLabel] = useState("");
  const [env, setEnv] = useState<"live" | "test">("live");
  const [scopes, setScopes] = useState<string[]>(["view", "collect", "refund"]);

  const load = () => {
    const q = envFilter === "all" ? "" : `?environment=${envFilter}`;
    api<ApiKey[]>(`/v1/developer/merchants/${merchantId}/keys${q}`)
      .then(setKeys)
      .catch((e) => setMsg(e.message));
  };

  useEffect(() => { load(); }, [merchantId, envFilter]);

  async function createKey() {
    try {
      const res = await api<{ apiKey: string }>(`/v1/developer/merchants/${merchantId}/keys`, {
        body: { label: label || "Default Key", environment: env, scopes },
      });
      setNewKeyPlaintext(res.apiKey);
      setShowModal(false);
      setLabel("");
      load();
    } catch (e) {
      setMsg(e instanceof Error ? e.message : String(e));
    }
  }

  async function revoke(id: string) {
    if (!confirm("Are you sure you want to revoke this API key? This cannot be undone.")) return;
    try {
      await api(`/v1/developer/merchants/${merchantId}/keys/${id}`, { method: "DELETE" });
      setMsg("API key revoked");
      load();
    } catch (e) {
      setMsg(e instanceof Error ? e.message : String(e));
    }
  }

  async function rotate(id: string) {
    if (!confirm("Rotate this API key? The old key will be revoked immediately and a new key generated.")) return;
    try {
      const res = await api<{ apiKey: string }>(`/v1/developer/merchants/${merchantId}/keys/${id}/rotate`, {
        body: { label: "Rotated Key" },
      });
      setNewKeyPlaintext(res.apiKey);
      setMsg("API key rotated successfully.");
      load();
    } catch (e) {
      setMsg(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <div>
      <div className="flex-between">
        <div className="flex-row">
          <label className="choice">
            Filter:
            <select value={envFilter} onChange={(e) => setEnvFilter(e.target.value)} style={{ width: "auto", display: "inline" }}>
              <option value="all">All Environments</option>
              <option value="live">Live Keys</option>
              <option value="test">Test / Sandbox Keys</option>
            </select>
          </label>
        </div>
        <button onClick={() => setShowModal(true)}>+ Generate New API Key</button>
      </div>

      {msg && <p className="notice ok">{msg}</p>}

      {newKeyPlaintext && (
        <div className="notice warn">
          <strong>New API Key Generated:</strong>
          <p className="mono" style={{ fontSize: "1.1rem", margin: ".5rem 0", background: "#fff", padding: ".5rem", borderRadius: "4px" }}>
            {newKeyPlaintext}
          </p>
          <p className="small">Copy this key now. It is hashed and will never be displayed again!</p>
          <button className="small" onClick={() => navigator.clipboard.writeText(newKeyPlaintext)}>Copy to Clipboard</button>
          <button className="small secondary" onClick={() => setNewKeyPlaintext(null)}>Dismiss</button>
        </div>
      )}

      <table className="grid" style={{ marginTop: "1rem" }}>
        <thead>
          <tr>
            <th>Prefix</th>
            <th>Env</th>
            <th>Label</th>
            <th>Scopes</th>
            <th>Created</th>
            <th>Status</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {keys.map((k) => (
            <tr key={k.id}>
              <td className="mono">{k.prefix}…</td>
              <td><span className={`tag ${k.environment === "live" ? "active" : "info"}`}>{k.environment}</span></td>
              <td>{k.label || "—"}</td>
              <td className="small">{k.scopes.join(", ")}</td>
              <td className="small">{new Date(k.createdAt).toLocaleDateString()}</td>
              <td>
                {k.revokedAt ? (
                  <span className="tag failed">Revoked</span>
                ) : (
                  <span className="tag active">Active</span>
                )}
              </td>
              <td>
                {!k.revokedAt && (
                  <>
                    <button className="small secondary" onClick={() => rotate(k.id)}>Rotate</button>
                    <button className="small secondary" style={{ color: "#a12a1f" }} onClick={() => revoke(k.id)}>Revoke</button>
                  </>
                )}
              </td>
            </tr>
          ))}
          {keys.length === 0 && (
            <tr><td colSpan={7} className="muted">No API keys found. Click "+ Generate New API Key" to create one.</td></tr>
          )}
        </tbody>
      </table>

      {showModal && (
        <div className="modal-backdrop">
          <div className="modal-content">
            <h3>Generate API Key</h3>
            <label>Environment
              <select value={env} onChange={(e) => setEnv(e.target.value as "live" | "test")}>
                <option value="live">Live (Production transactions)</option>
                <option value="test">Test / Sandbox (Simulation)</option>
              </select>
            </label>
            <label>Key Label / Application Name
              <input value={label} onChange={(e) => setLabel(e.target.value)} placeholder="e.g. WooCommerce Store Main" />
            </label>
            <fieldset>
              <legend>Permissions / Scopes</legend>
              {["view", "collect", "refund", "withdraw", "payouts"].map((sc) => (
                <label key={sc} className="choice">
                  <input
                    type="checkbox"
                    checked={scopes.includes(sc)}
                    onChange={(e) => {
                      if (e.target.checked) setScopes([...scopes, sc]);
                      else setScopes(scopes.filter((x) => x !== sc));
                    }}
                  />
                  {sc}
                </label>
              ))}
            </fieldset>
            <div style={{ marginTop: "1rem" }}>
              <button onClick={() => void createKey()}>Generate Key</button>
              <button className="secondary" onClick={() => setShowModal(false)}>Cancel</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// -------------------------------------------------------------------------------------------------
// 2. Webhooks View
// -------------------------------------------------------------------------------------------------
function WebhooksView({ merchantId }: { merchantId: string }) {
  const [endpoints, setEndpoints] = useState<WebhookEndpoint[]>([]);
  const [msg, setMsg] = useState<string | null>(null);
  const [showAdd, setShowAdd] = useState(false);
  const [url, setUrl] = useState("");
  const [env, setEnv] = useState<"live" | "test">("live");
  const [desc, setDesc] = useState("");
  const [selectedEvents, setSelectedEvents] = useState<string[]>([
    "checkout.paid", "payment.failed", "payout.completed", "refund.created"
  ]);

  const load = () => {
    api<WebhookEndpoint[]>(`/v1/developer/merchants/${merchantId}/webhooks`)
      .then(setEndpoints)
      .catch((e) => setMsg(e.message));
  };

  useEffect(() => { load(); }, [merchantId]);

  async function register() {
    try {
      await api(`/v1/developer/merchants/${merchantId}/webhooks`, {
        body: { url, environment: env, description: desc, eventTypes: selectedEvents },
      });
      setMsg("Webhook endpoint registered successfully.");
      setShowAdd(false);
      setUrl(""); setDesc("");
      load();
    } catch (e) {
      setMsg(e instanceof Error ? e.message : String(e));
    }
  }

  async function ping(id: string) {
    try {
      const res = await api<{ success: boolean; statusCode: number; durationMs: number }>(
        `/v1/developer/merchants/${merchantId}/webhooks/${id}/ping`,
        { method: "POST" }
      );
      setMsg(`Ping test completed: HTTP ${res.statusCode} in ${res.durationMs}ms (${res.success ? "Success" : "Failed"})`);
      load();
    } catch (e) {
      setMsg(e instanceof Error ? e.message : String(e));
    }
  }

  async function rotateSecret(id: string) {
    if (!confirm("Rotate webhook signing secret? You will need to update your application server immediately.")) return;
    try {
      const res = await api<{ secret: string }>(`/v1/developer/merchants/${merchantId}/webhooks/${id}/secret/rotate`, { method: "POST" });
      setMsg(`New secret generated: ${res.secret}`);
      load();
    } catch (e) {
      setMsg(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <div>
      <div className="flex-between">
        <p className="muted">ELEPAY sends signed JSON payloads to your endpoints with the <code className="mono">Elepay-Signature</code> HMAC header.</p>
        <button onClick={() => setShowAdd(true)}>+ Register Webhook Endpoint</button>
      </div>

      {msg && <p className="notice ok">{msg}</p>}

      <table className="grid" style={{ marginTop: "1rem" }}>
        <thead>
          <tr>
            <th>URL</th>
            <th>Env</th>
            <th>Subscribed Events</th>
            <th>Signing Secret</th>
            <th>Status / Failures</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {endpoints.map((ep) => (
            <tr key={ep.id}>
              <td>
                <div className="mono small">{ep.url}</div>
                {ep.description && <div className="muted small">{ep.description}</div>}
              </td>
              <td><span className={`tag ${ep.environment === "live" ? "active" : "info"}`}>{ep.environment}</span></td>
              <td className="small">{ep.eventTypes.join(", ")}</td>
              <td>
                <div className="mono small">{ep.secret.substring(0, 12)}…</div>
                <button className="small secondary" onClick={() => navigator.clipboard.writeText(ep.secret)}>Copy</button>
                <button className="small secondary" onClick={() => rotateSecret(ep.id)}>Rotate</button>
              </td>
              <td>
                {ep.active ? <span className="tag active">Active</span> : <span className="tag warn">Disabled</span>}
                {ep.failureCount > 0 && <span className="tag failed" style={{ marginLeft: ".25rem" }}>{ep.failureCount} fails</span>}
              </td>
              <td>
                <button className="small" onClick={() => ping(ep.id)}>Test Ping</button>
              </td>
            </tr>
          ))}
          {endpoints.length === 0 && (
            <tr><td colSpan={6} className="muted">No webhook endpoints registered. Click "+ Register Webhook Endpoint" to add one.</td></tr>
          )}
        </tbody>
      </table>

      {showAdd && (
        <div className="modal-backdrop">
          <div className="modal-content">
            <h3>Register Webhook Endpoint</h3>
            <label>Endpoint URL (must begin with https://)
              <input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://api.myshop.com/webhooks/elepay" required />
            </label>
            <label>Environment
              <select value={env} onChange={(e) => setEnv(e.target.value as "live" | "test")}>
                <option value="live">Live (Production)</option>
                <option value="test">Test / Sandbox</option>
              </select>
            </label>
            <label>Description / Name
              <input value={desc} onChange={(e) => setDesc(e.target.value)} placeholder="e.g. Primary ERP webhook" />
            </label>
            <fieldset>
              <legend>Subscribed Events</legend>
              {["checkout.paid", "payment.failed", "payout.completed", "refund.created", "mandate.active"].map((ev) => (
                <label key={ev} className="choice">
                  <input
                    type="checkbox"
                    checked={selectedEvents.includes(ev)}
                    onChange={(e) => {
                      if (e.target.checked) setSelectedEvents([...selectedEvents, ev]);
                      else setSelectedEvents(selectedEvents.filter((x) => x !== ev));
                    }}
                  />
                  {ev}
                </label>
              ))}
            </fieldset>
            <div style={{ marginTop: "1rem" }}>
              <button onClick={() => void register()}>Save Endpoint</button>
              <button className="secondary" onClick={() => setShowAdd(false)}>Cancel</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// -------------------------------------------------------------------------------------------------
// 3. Webhook Delivery Logs & Manual Retry Replay
// -------------------------------------------------------------------------------------------------
function DeliveryLogsView({ merchantId }: { merchantId: string }) {
  const [logs, setLogs] = useState<WebhookLog[]>([]);
  const [statusFilter, setStatusFilter] = useState("all");
  const [inspectLog, setInspectLog] = useState<WebhookLog | null>(null);
  const [msg, setMsg] = useState<string | null>(null);

  const load = () => {
    const q = statusFilter === "all" ? "" : `?status=${statusFilter}`;
    api<WebhookLog[]>(`/v1/developer/merchants/${merchantId}/webhooks/deliveries${q}`)
      .then(setLogs)
      .catch((e) => setMsg(e.message));
  };

  useEffect(() => { load(); }, [merchantId, statusFilter]);

  async function replay(id: string) {
    try {
      const res = await api<{ success: boolean; statusCode: number; durationMs: number }>(
        `/v1/developer/merchants/${merchantId}/webhooks/deliveries/${id}/replay`,
        { method: "POST" }
      );
      setMsg(`Manual retry executed: Status HTTP ${res.statusCode} (${res.success ? "DELIVERED" : "FAILED"}) in ${res.durationMs}ms`);
      load();
      if (inspectLog && inspectLog.id === id) setInspectLog(null);
    } catch (e) {
      setMsg(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <div>
      <div className="flex-between">
        <div className="flex-row">
          <label className="choice">
            Status:
            <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} style={{ width: "auto", display: "inline" }}>
              <option value="all">All Deliveries</option>
              <option value="DELIVERED">Delivered (2xx)</option>
              <option value="FAILED">Failed</option>
              <option value="DEAD">Dead (Max retries)</option>
            </select>
          </label>
        </div>
        <button className="small secondary" onClick={() => load()}>Refresh Logs</button>
      </div>

      {msg && <p className="notice ok">{msg}</p>}

      <table className="grid" style={{ marginTop: "1rem" }}>
        <thead>
          <tr>
            <th>Timestamp</th>
            <th>Event</th>
            <th>URL</th>
            <th>HTTP Status</th>
            <th>Duration</th>
            <th>Status</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          {logs.map((l) => (
            <tr key={l.id}>
              <td className="small">{new Date(l.createdAt).toLocaleTimeString()}</td>
              <td className="mono small">{l.eventType}</td>
              <td className="mono small">{l.requestUrl}</td>
              <td>
                <span className={`tag ${l.responseCode && l.responseCode >= 200 && l.responseCode < 300 ? "active" : "failed"}`}>
                  {l.responseCode ?? "ERR"}
                </span>
              </td>
              <td className="small num">{l.durationMs}ms</td>
              <td>
                <span className={`tag ${l.status === "DELIVERED" ? "active" : "failed"}`}>{l.status}</span>
                {l.isManualRetry && <span className="tag info" style={{ marginLeft: ".25rem" }}>Retry</span>}
              </td>
              <td>
                <button className="small secondary" onClick={() => setInspectLog(l)}>Inspect</button>
                <button className="small" onClick={() => replay(l.id)}>Replay</button>
              </td>
            </tr>
          ))}
          {logs.length === 0 && (
            <tr><td colSpan={7} className="muted">No delivery logs recorded yet. Send a test ping to generate one.</td></tr>
          )}
        </tbody>
      </table>

      {inspectLog && (
        <div className="modal-backdrop">
          <div className="modal-content" style={{ maxWidth: "800px" }}>
            <div className="flex-between">
              <h3>Delivery Inspection ({inspectLog.eventType})</h3>
              <button className="small secondary" onClick={() => setInspectLog(null)}>✕ Close</button>
            </div>
            <p><strong>Endpoint URL:</strong> <code className="mono">{inspectLog.requestUrl}</code></p>
            <p><strong>Status:</strong> <span className={`tag ${inspectLog.status === "DELIVERED" ? "active" : "failed"}`}>{inspectLog.status}</span> (HTTP {inspectLog.responseCode}) in {inspectLog.durationMs}ms</p>

            <h4>Request Headers</h4>
            <div className="code-box">{inspectLog.requestHeaders}</div>

            <h4>Request Payload (JSON)</h4>
            <div className="code-box">{inspectLog.requestPayload}</div>

            <h4>Response Body</h4>
            <div className="code-box">{inspectLog.responseBody || "No response body received."}</div>

            <div style={{ marginTop: "1rem" }}>
              <button onClick={() => replay(inspectLog.id)}>Replay Delivery Now</button>
              <button className="secondary" onClick={() => setInspectLog(null)}>Close</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// -------------------------------------------------------------------------------------------------
// 4. Interactive API Explorer View
// -------------------------------------------------------------------------------------------------
function ApiExplorerView({ merchantId }: { merchantId: string }) {
  const [selectedEndpoint, setSelectedEndpoint] = useState("create_checkout");
  const [reqBody, setReqBody] = useState(`{
  "currency": "GHS",
  "lines": [
    { "kind": "sale", "description": "Order #5001", "amount": 2500 }
  ]
}`);
  const [resData, setResData] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const ENDPOINTS = [
    { id: "create_checkout", method: "POST", path: "/v1/checkouts", label: "Create Checkout Session" },
    { id: "get_checkout_status", method: "GET", path: "/v1/public/checkouts/YOUR_REF/status", label: "Get Checkout Status" },
    { id: "get_balances", method: "GET", path: `/v1/merchants/${merchantId}/balances`, label: "Get Merchant Balances" },
    { id: "list_transactions", method: "GET", path: `/v1/merchants/${merchantId}/transactions`, label: "List Transactions" },
  ];

  async function execute() {
    setLoading(true);
    setResData(null);
    try {
      const ep = ENDPOINTS.find((x) => x.id === selectedEndpoint);
      if (!ep) return;
      let bodyData = undefined;
      if (ep.method === "POST" && reqBody.trim()) {
        bodyData = JSON.parse(reqBody);
      }
      const res = await api(ep.path, { method: ep.method, body: bodyData, idempotencyKey: newKey() });
      setResData(JSON.stringify(res, null, 2));
    } catch (e) {
      setResData(JSON.stringify(e instanceof Error ? { error: e.message } : e, null, 2));
    } finally {
      setLoading(false);
    }
  }

  const ep = ENDPOINTS.find((x) => x.id === selectedEndpoint)!;

  return (
    <div>
      <h3>Interactive API Explorer</h3>
      <p className="muted">Test ELEPAY endpoints directly from your browser with live session credentials.</p>

      <label>Select Endpoint
        <select value={selectedEndpoint} onChange={(e) => setSelectedEndpoint(e.target.value)}>
          {ENDPOINTS.map((x) => (
            <option key={x.id} value={x.id}>[{x.method}] {x.path} — {x.label}</option>
          ))}
        </select>
      </label>

      {ep.method === "POST" && (
        <label>Request Body (JSON)
          <textarea
            rows={8}
            className="mono"
            style={{ width: "100%", padding: ".5rem", borderRadius: "6px", border: "1px solid var(--line)" }}
            value={reqBody}
            onChange={(e) => setReqBody(e.target.value)}
          />
        </label>
      )}

      <button onClick={() => void execute()} disabled={loading}>
        {loading ? "Executing…" : `Send ${ep.method} Request`}
      </button>

      {resData && (
        <div style={{ marginTop: "1rem" }}>
          <h4>Response</h4>
          <div className="code-box">{resData}</div>
        </div>
      )}
    </div>
  );
}

// -------------------------------------------------------------------------------------------------
// 5. Client SDK Code Snippets View
// -------------------------------------------------------------------------------------------------
function SdkSnippetsView() {
  const [lang, setLang] = useState("nodejs");
  const [action, setAction] = useState("create_checkout");
  const [snippet, setSnippet] = useState<string>("Loading…");
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    api<{ code: string }>(`/v1/developer/snippets?action=${action}&language=${lang}`)
      .then((res) => setSnippet(res.code))
      .catch(() => setSnippet("// Snippet generation unavailable"));
  }, [lang, action]);

  return (
    <div>
      <div className="flex-between">
        <h3>SDK Code Snippets</h3>
        <button
          className="small secondary"
          onClick={() => {
            navigator.clipboard.writeText(snippet);
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
          }}
        >
          {copied ? "✓ Copied" : "Copy Code"}
        </button>
      </div>

      <div className="flex-row" style={{ margin: "1rem 0" }}>
        <label className="choice">Action:
          <select value={action} onChange={(e) => setAction(e.target.value)} style={{ width: "auto" }}>
            <option value="create_checkout">Create Checkout Session</option>
            <option value="verify_payment">Verify Payment Status</option>
            <option value="create_refund">Create Refund</option>
            <option value="register_webhook">Register Webhook</option>
          </select>
        </label>

        <label className="choice">Language:
          <select value={lang} onChange={(e) => setLang(e.target.value)} style={{ width: "auto" }}>
            <option value="nodejs">Node.js / TypeScript</option>
            <option value="python">Python</option>
            <option value="php">PHP</option>
            <option value="java">Java 17+</option>
            <option value="curl">cURL</option>
          </select>
        </label>
      </div>

      <div className="code-box">{snippet}</div>
    </div>
  );
}

// -------------------------------------------------------------------------------------------------
// 6. WooCommerce Plugin View
// -------------------------------------------------------------------------------------------------
function WooCommerceView({ merchantId }: { merchantId: string }) {
  return (
    <div className="cards" style={{ gridTemplateColumns: "1fr" }}>
      <section className="panel">
        <div className="flex-between">
          <div>
            <h3>Official ELEPAY WooCommerce Payment Gateway</h3>
            <p className="muted">Direct integration with Ghanaian payment rails: MTN MoMo, Telecel Cash, AT Money, GhIPSS GhQR &amp; Direct Card.</p>
          </div>
          <span className="tag active">v1.2.0 Direct Rails</span>
        </div>

        <hr style={{ border: "0", borderTop: "1px solid var(--line)", margin: "1rem 0" }} />

        <h4>1. Direct Domestic Payment Rails</h4>
        <p>Integrates directly with Ghana's domestic payment infrastructure without intermediate aggregators:</p>
        <ul>
          <li><strong>MTN Mobile Money (MoMo):</strong> Direct API push prompt integration.</li>
          <li><strong>Telecel Cash:</strong> Direct subscriber prompt and voucher support.</li>
          <li><strong>AT Money:</strong> Real-time wallet collection across AT network.</li>
          <li><strong>GhIPSS GhQR:</strong> Universal national QR code interoperable across all banks and mobile wallets.</li>
          <li><strong>Direct Card:</strong> Visa, Mastercard, and Gh-Link card acquiring.</li>
        </ul>

        <h4>2. Plugin Installation</h4>
        <p>The plugin is located in <code className="mono">plugins/woocommerce-elepay/</code> in your repository.</p>
        <ol>
          <li>Copy the <code className="mono">plugins/woocommerce-elepay</code> folder into your WordPress site's <code className="mono">/wp-content/plugins/</code> directory.</li>
          <li>In WordPress Admin, navigate to <strong>Plugins &gt; Installed Plugins</strong> and click <strong>Activate</strong> on ELEPAY Payment Gateway.</li>
        </ol>

        <h4>3. WooCommerce Settings Configuration</h4>
        <p>Navigate to <strong>WooCommerce &gt; Settings &gt; Payments &gt; ELEPAY</strong> and enter your credentials:</p>
        <table className="grid">
          <tbody>
            <tr><td><strong>Merchant UUID</strong></td><td className="mono">{merchantId}</td></tr>
            <tr><td><strong>Test / Live Mode</strong></td><td>Select Test for Sandbox, Live for Production.</td></tr>
            <tr><td><strong>Request Signing Secret</strong></td><td>Your HMAC-SHA256 v2 signing secret for outbound API requests.</td></tr>
            <tr><td><strong>Webhook Notification URL</strong></td><td className="mono">https://YOUR_DOMAIN.com/wc-api/wc_gateway_elepay</td></tr>
          </tbody>
        </table>

        <h4>4. Security &amp; Compliance Highlights</h4>
        <ul>
          <li><strong>HMAC-SHA256 v2 Request Signing:</strong> All checkout and refund API requests from WooCommerce are signed using canonical v2 format (<code className="mono">X-Elepay-Signature-Version: 2</code>, <code className="mono">X-Elepay-Timestamp</code>, and <code className="mono">X-Elepay-Nonce</code>) with distributed replay protection.</li>
          <li><strong>Cryptographic Webhook Signatures:</strong> Every incoming notification is validated via HMAC-SHA256 signature verification with 300-second timestamp drift protection against replay attacks.</li>
          <li><strong>WooCommerce Blocks:</strong> Full compatibility with modern Gutenberg Cart &amp; Checkout Blocks.</li>
          <li><strong>HPOS Compatible:</strong> Declared compatibility with High-Performance Order Storage (Custom Order Tables).</li>
          <li><strong>One-Click Refunds:</strong> Issue full or partial refunds directly from the WooCommerce Order Management dashboard.</li>
        </ul>
      </section>
    </div>
  );
}
