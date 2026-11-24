import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "./api";

interface MerchantView { id:string; tradingName:string; merchantCode:string; role:string; status:string }
interface ApiKey { id:string; prefix:string; environment:"live"|"test"; label:string|null; scopes:string[]; createdAt:string; revokedAt:string|null; lastUsedAt:string|null }
interface CreatedKey { id:string; prefix:string; apiKey:string; signingSecret:string|null; environment:string; scopes:string[] }
interface WebhookEndpoint { id:string; url:string; environment:string; description:string|null; eventTypes:string[]; active:boolean; failureCount:number; lastDeliveryAt:string|null; createdAt:string }
interface WebhookLog { id:string; deliveryId:string; endpointId:string; eventId:string; merchantId:string; eventType:string; requestUrl:string; responseCode:number|null; durationMs:number; status:string; attemptNumber:number; isManualRetry:boolean; createdAt:string }
interface ReplayResult { newLogId:string; success:boolean; statusCode:number; durationMs:number }
type KeyType="publishable"|"secret"|"payout";
const DEV_TABS=["API Keys","Webhooks","Delivery Logs & Replay","API Explorer","SDK Snippets","WooCommerce Plugin"] as const;
const EVENTS=["checkout.paid","payment.failed","payout.completed","refund.created","mandate.active"];
const timeout=()=>AbortSignal.timeout(20000);
const date=(value:string|null)=>value&&Number.isFinite(Date.parse(value))?new Intl.DateTimeFormat("en-GH",{dateStyle:"medium",timeStyle:"short",timeZone:"Africa/Accra"}).format(new Date(value)):"—";
const msg=(e:unknown)=>e instanceof Error?e.message:"Request could not be verified.";
const base=(id:string)=>"/v1/developer/merchants/"+encodeURIComponent(id);
const manage=(m:MerchantView)=>["owner","admin"].includes(m.role);

export function keyScopes(type:KeyType):string[] {
  return type==="publishable"?["collect"]:type==="secret"?["view","collect","refund"]:["view","withdraw","payouts"];
}
export function validateKeyInput(type:KeyType,scopes:string[],ips:string[]):string|null {
  if(!scopes.length||scopes.some(s=>!keyScopes(type).includes(s)))return "Select valid, least-privilege scopes for this key type.";
  if(type==="publishable"&&(scopes.length!==1||scopes[0]!=="collect"))return "Publishable keys are limited to collect.";
  if(type==="payout"&&(!ips.length||ips.some(ip=>ip==="0.0.0.0/0"||ip==="::/0")))return "Payout keys require a restricted IP or CIDR allowlist.";
  return null;
}
export function validateWebhookInput(url:string,selectedEvents:string[]):string|null {
  if(!url.trim())return "Webhook URL is required.";
  try{
    const p=new URL(url.trim());
    if(p.protocol!=="https:")return "Webhook URL must begin with https://";
    if(p.username||p.password||p.hash||p.search)return "Use an HTTPS endpoint without credentials, query strings or fragments.";
    if(p.hostname==="localhost"||p.hostname.endsWith(".localhost")||p.hostname.endsWith(".local")||!p.hostname.includes(".")||/^\d+\.\d+\.\d+\.\d+$/.test(p.hostname))return "Use a public DNS hostname. The server also validates network destinations.";
  }catch{return "Enter a valid https:// webhook URL.";}
  if(url.trim().length>500)return "Webhook URL is too long.";
  if(!selectedEvents.length||selectedEvents.some(e=>!EVENTS.includes(e)))return "Select at least one supported event.";
  return null;
}
export function safeDeveloperKeyPrefix(prefix:string):boolean {return /^(?:pk|sk|payout|ek)_(?:live|test)_/.test(prefix);}
export function canReplayDelivery(status:string):boolean {return ["FAILED","DEAD"].includes(status.toUpperCase());}
export function explorerPath(id:string,endpoint:string,reference:string):string|null {
  const root="/v1/merchants/"+encodeURIComponent(id);
  if(endpoint==="balances")return root+"/balances";
  if(endpoint==="transactions")return root+"/transactions?limit=50";
  if(endpoint==="checkouts")return root+"/checkouts?limit=50";
  if(endpoint==="checkout"&&/^[A-Za-z0-9_-]{1,120}$/.test(reference.trim()))return root+"/checkouts/"+encodeURIComponent(reference.trim());
  return null;
}
// Whitelist list fields before retaining data in React state. The API currently returns additional sensitive fields.
function endpointMetadata(e:WebhookEndpoint):WebhookEndpoint {const {id,url,environment,description,eventTypes,active,failureCount,lastDeliveryAt,createdAt}=e;return {id,url,environment,description,eventTypes,active,failureCount,lastDeliveryAt,createdAt};}
function deliveryMetadata(e:WebhookLog):WebhookLog {const {id,deliveryId,endpointId,eventId,merchantId,eventType,requestUrl,responseCode,durationMs,status,attemptNumber,isManualRetry,createdAt}=e;return {id,deliveryId,endpointId,eventId,merchantId,eventType,requestUrl,responseCode,durationMs,status,attemptNumber,isManualRetry,createdAt};}
function useRows<T>(path:string, project?:(row:T)=>T){
  const [data,setData]=useState<T[]|null>(null),[error,setError]=useState<string|null>(null),[loading,setLoading]=useState(true);
  const sequence=useRef(0);
  const load=useCallback(async()=>{const n=++sequence.current;setLoading(true);setError(null);setData(null);try{const rows=await api<T[]>(path,{signal:timeout()});if(!Array.isArray(rows))throw new Error("Unexpected list response");if(n===sequence.current)setData(project?rows.map(project):rows);}catch(e){if(n===sequence.current)setError(msg(e));}finally{if(n===sequence.current)setLoading(false);}},[path,project]);
  useEffect(()=>{void load();return()=>{sequence.current++;};},[load]);
  return {data,error,loading,load};
}
function useAction(){
  const [busy,setBusy]=useState(false),[error,setError]=useState<string|null>(null),[message,setMessage]=useState<string|null>(null);
  const lock=useRef(false);
  async function run(task:()=>Promise<void>){if(lock.current)return;lock.current=true;setBusy(true);setError(null);setMessage(null);try{await task();}catch(e){setError("Request not confirmed. Refresh records before trying again. "+msg(e));}finally{lock.current=false;setBusy(false);}}
  return {busy,error,message,setMessage,setError,run};
}
function Feedback({error,message}:{error?:string|null;message?:string|null}){return <>{error&&<p className="notice warn" role="alert">{error}</p>}{message&&<p className="notice" role="status">{message}</p>}</>;}
function Empty({loading,error,label,colSpan}:{loading:boolean;error:string|null;label:string;colSpan:number}){return <tr><td colSpan={colSpan} className="muted">{loading?"Loading…":error?"Records unavailable.":label}</td></tr>;}
function SecretPanel({values,onDismiss}:{values:{label:string;value:string}[];onDismiss:()=>void}){
  const [show,setShow]=useState(false),[feedback,setFeedback]=useState("");
  useEffect(()=>{const hide=()=>{if(document.visibilityState!=="visible")setShow(false);};const blur=()=>setShow(false);document.addEventListener("visibilitychange",hide);window.addEventListener("blur",blur);return()=>{document.removeEventListener("visibilitychange",hide);window.removeEventListener("blur",blur);};},[]);
  async function copy(value:string){try{await navigator.clipboard.writeText(value);setFeedback("Copied. Clear your clipboard after securely storing it.");}catch{setFeedback("Copy failed. Reveal and copy manually in a private environment.");}}
  return <section className="notice warn dev-secret"><h3>Save the new credentials now</h3><p>Keep server credentials in a secret manager. Do not paste them into client-side code, logs or chat. Leaving this section discards the values from this view.</p>{values.map(v=><div key={v.label}><label>{v.label}<input readOnly type={show?"text":"password"} value={v.value} autoComplete="off" spellCheck={false}/></label><button className="secondary small" onClick={()=>void copy(v.value)}>Copy {v.label}</button></div>)}<button className="secondary" onClick={()=>setShow(!show)}>{show?"Hide credentials":"Reveal credentials"}</button><button onClick={onDismiss}>Dismiss after saving</button><p role="status" className="small">{feedback}</p></section>;
}
export function DeveloperPortal(){
  const merchants=useRows<MerchantView>("/v1/merchants");
  const [id,setId]=useState(""),[tab,setTab]=useState<typeof DEV_TABS[number]>("API Keys");
  const eligible=(merchants.data??[]).filter(manage),m=eligible.find(x=>x.id===id)??eligible[0];
  return <main className="developer-workspace"><div className="page-title"><div><p className="eyebrow">ELEPAY DEVELOPERS</p><h1>Integration workspace</h1><p className="muted">Configure credentials, delivery endpoints and server-side integrations.</p></div><button className="secondary" disabled={merchants.loading} onClick={()=>void merchants.load()}>Refresh businesses</button></div><Feedback error={merchants.error}/>
  {!m?<section className="panel"><h2>{merchants.loading?"Loading businesses":"Developer access unavailable"}</h2><p>API credential and webhook management requires merchant owner or administrator permissions.</p></section>:<>
  <section className="panel merchant-context"><div><p className="eyebrow">CURRENT BUSINESS</p><h2>{m.tradingName}</h2><p className="small muted">Role: {m.role} · Status: {m.status}</p></div><label>Business<select value={m.id} onChange={e=>{setId(e.target.value);setTab("API Keys");}}>{eligible.map(x=><option key={x.id} value={x.id}>{x.tradingName}</option>)}</select></label></section>
  <div className="merchant-shell"><aside className="panel merchant-sidebar"><nav aria-label="Developer sections">{DEV_TABS.map(t=><button key={t} className={tab===t?"active":""} aria-pressed={tab===t} onClick={()=>setTab(t)}>{t}</button>)}</nav></aside><div className="merchant-content" key={m.id+":"+tab}>
  {tab==="API Keys"&&<ApiKeysView merchantId={m.id}/>}
  {tab==="Webhooks"&&<WebhooksView merchantId={m.id}/>}
  {tab==="Delivery Logs & Replay"&&<DeliveryLogsView merchantId={m.id}/>}
  {tab==="API Explorer"&&<ApiExplorerView merchantId={m.id}/>}
  {tab==="SDK Snippets"&&<SdkSnippetsView merchantId={m.id}/>}
  {tab==="WooCommerce Plugin"&&<WooCommerceView merchantId={m.id}/>}
  </div></div></>}</main>;
}
function ApiKeysView({merchantId}:{merchantId:string}){
  const [filter,setFilter]=useState("all"),[label,setLabel]=useState(""),[env,setEnv]=useState<"live"|"test">("live"),[type,setType]=useState<KeyType>("secret"),[scopes,setScopes]=useState<string[]>(["view"]),[ips,setIps]=useState(""),[show,setShow]=useState(false),[credentials,setCredentials]=useState<{label:string;value:string}[]>([]),[confirm,setConfirm]=useState<{kind:"rotate"|"revoke";id:string;prefix:string}|null>(null);
  const rows=useRows<ApiKey>(base(merchantId)+"/keys"+(filter==="all"?"":"?environment="+filter));
  const action=useAction();
  function accept(r:CreatedKey){if(!r||typeof r.apiKey!=="string"||!r.apiKey||typeof r.prefix!=="string"||!safeDeveloperKeyPrefix(r.prefix)||!r.apiKey.startsWith(r.prefix)||(!r.prefix.startsWith("pk_")&&(!r.signingSecret||typeof r.signingSecret!=="string")))throw new Error("Credential response incomplete. Review the key list and revoke any unusable key.");setCredentials([{label:"API key",value:r.apiKey},...(r.signingSecret?[{label:"Request signing secret",value:r.signingSecret}]:[])]);setShow(false);setConfirm(null);void rows.load();}
  function create(e:React.FormEvent){e.preventDefault();const allow=ips.split(",").map(s=>s.trim()).filter(Boolean);const err=validateKeyInput(type,scopes,allow);if(err){action.setError(err);return;}void action.run(async()=>{accept(await api<CreatedKey>(base(merchantId)+"/keys",{body:{label:label.trim()||null,environment:env,keyType:type,scopes,allowedIps:allow},signal:timeout()}));});}
  function change(){if(!confirm)return;const target=confirm;void action.run(async()=>{if(target.kind==="rotate"){accept(await api<CreatedKey>(base(merchantId)+"/keys/"+target.id+"/rotate",{body:{},signal:timeout()}));}else{const r=await api<{status:string}>(base(merchantId)+"/keys/"+target.id,{method:"DELETE",signal:timeout()});if(r.status!=="revoked")throw new Error("Revocation not confirmed.");setConfirm(null);action.setMessage("Credential revoked.");void rows.load();}});}
  return <section className="panel"><div className="page-title"><div><h2>API credentials</h2><p className="muted small">Live keys authorize real requests. Test credentials require an isolated test deployment.</p></div><button disabled={action.busy||credentials.length>0} onClick={()=>setShow(!show)}>{show?"Close form":"Generate API key"}</button></div><Feedback error={rows.error||action.error} message={action.message}/>
  {credentials.length>0&&<SecretPanel values={credentials} onDismiss={()=>setCredentials([])}/>}
  {show&&credentials.length===0&&<form className="dev-form" onSubmit={create}><h3>Create least-privilege credential</h3><fieldset disabled={action.busy}><label>Label<input maxLength={100} value={label} onChange={e=>setLabel(e.target.value)}/></label><label>Environment<select value={env} onChange={e=>setEnv(e.target.value as "live"|"test")}><option value="live">Live</option><option value="test">Isolated test deployment only</option></select></label><label>Key type<select value={type} onChange={e=>{const t=e.target.value as KeyType;setType(t);setScopes(t==="publishable"?["collect"]:["view"]);}}><option value="publishable">Publishable</option><option value="secret">Server secret</option><option value="payout">Payout credential</option></select></label><fieldset><legend>Permissions</legend>{keyScopes(type).map(s=><label className="choice" key={s}><input type="checkbox" checked={scopes.includes(s)} disabled={type==="publishable"} onChange={e=>setScopes(old=>e.target.checked?[...old,s]:old.filter(x=>x!==s))}/>{s}</label>)}</fieldset><label>Allowed IPs / CIDRs {type==="payout"?"(required)":"(optional)"}<input value={ips} required={type==="payout"} onChange={e=>setIps(e.target.value)} placeholder="203.0.113.10/32"/></label><button type="submit">{action.busy?"Generating…":"Generate credential"}</button></fieldset></form>}
  {confirm&&<div className="notice warn"><h3>{confirm.kind==="rotate"?"Rotate":"Revoke"} {confirm.prefix}…</h3><p>The old key will stop working. Prepare your server deployment before confirming.</p><button disabled={action.busy} onClick={change}>Confirm {confirm.kind}</button><button className="secondary" disabled={action.busy} onClick={()=>setConfirm(null)}>Cancel</button></div>}
  <div className="wallet-filter"><label>Environment<select value={filter} onChange={e=>setFilter(e.target.value)}><option value="all">All</option><option value="live">Live</option><option value="test">Test</option></select></label><button className="secondary" disabled={rows.loading||action.busy} onClick={()=>void rows.load()}>Refresh</button></div>
  <div className="table-scroll" role="region" aria-label="API credentials" tabIndex={0}><table className="grid"><thead><tr><th>Prefix / label</th><th>Environment</th><th>Scopes</th><th>Created (GMT)</th><th>Last used (GMT)</th><th>Status</th><th>Actions</th></tr></thead><tbody>{(rows.data??[]).map(k=><tr key={k.id}><td><span className="mono">{k.prefix}…</span><div className="small muted">{k.label??"—"}</div></td><td>{k.environment}</td><td>{k.scopes.join(", ")}</td><td>{date(k.createdAt)}</td><td>{date(k.lastUsedAt)}</td><td>{k.revokedAt?"Revoked":"Active"}</td><td>{!k.revokedAt&&safeDeveloperKeyPrefix(k.prefix)&&<><button className="secondary small" disabled={action.busy||credentials.length>0} onClick={()=>setConfirm({kind:"rotate",id:k.id,prefix:k.prefix})}>Rotate</button><button className="secondary small" disabled={action.busy||credentials.length>0} onClick={()=>setConfirm({kind:"revoke",id:k.id,prefix:k.prefix})}>Revoke</button></>}</td></tr>)}{!rows.data?.length&&<Empty {...rows} colSpan={7} label="No credentials found."/>}</tbody></table></div></section>;
}

function WebhooksView({merchantId}:{merchantId:string}){
  const rows=useRows<WebhookEndpoint>(base(merchantId)+"/webhooks",endpointMetadata);
  const [show,setShow]=useState(false),[url,setUrl]=useState(""),[description,setDescription]=useState(""),[env,setEnv]=useState<"live"|"test">("live"),[events,setEvents]=useState<string[]>(["checkout.paid"]),[secret,setSecret]=useState<string|null>(null),[confirm,setConfirm]=useState<{kind:"rotate"|"toggle";endpoint:WebhookEndpoint}|null>(null);
  const action=useAction();
  function register(e:React.FormEvent){e.preventDefault();const error=validateWebhookInput(url,events);if(error){action.setError(error);return;}void action.run(async()=>{const r=await api<{id:string;secret:string}>(base(merchantId)+"/webhooks",{body:{url:url.trim(),environment:env,description:description.trim(),eventTypes:events},signal:timeout()});if(!r?.id||!r.secret)throw new Error("Endpoint or signing secret unavailable.");setSecret(r.secret);setShow(false);setUrl("");setDescription("");void rows.load();});}
  function change(){if(!confirm)return;const c=confirm;void action.run(async()=>{const path=base(merchantId)+"/webhooks/"+c.endpoint.id;if(c.kind==="rotate"){const r=await api<{secret:string}>(path+"/secret/rotate",{method:"POST",signal:timeout()});if(!r?.secret)throw new Error("Secret rotation not confirmed.");setSecret(r.secret);}else{const r=await api<{updated:boolean}>(path,{method:"PUT",body:{active:!c.endpoint.active},signal:timeout()});if(!r.updated)throw new Error("Endpoint update not confirmed.");action.setMessage(c.endpoint.active?"Endpoint disabled.":"Endpoint enabled.");}setConfirm(null);void rows.load();});}
  function ping(ep:WebhookEndpoint){void action.run(async()=>{const r=await api<ReplayResult>(base(merchantId)+"/webhooks/"+ep.id+"/ping",{method:"POST",signal:timeout()});if(typeof r?.success!=="boolean")throw new Error("Ping result not verified.");action.setMessage("Ping "+(r.success?"delivered":"failed")+" · HTTP "+r.statusCode+" · "+r.durationMs+" ms");void rows.load();});}
  return <section className="panel"><div className="page-title"><div><h2>Webhook endpoints</h2><p className="small muted">Signed event delivery. Verify HMAC against the raw request body and deduplicate event IDs before processing.</p></div><button disabled={action.busy||!!secret} onClick={()=>setShow(!show)}>{show?"Close form":"Register endpoint"}</button></div><Feedback error={rows.error||action.error} message={action.message}/>
  {secret&&<SecretPanel values={[{label:"Webhook signing secret",value:secret}]} onDismiss={()=>setSecret(null)}/>}
  {show&&!secret&&<form className="dev-form" onSubmit={register}><fieldset disabled={action.busy}><label>HTTPS endpoint<input type="url" required maxLength={500} value={url} onChange={e=>setUrl(e.target.value)} placeholder="https://your-service.example/webhooks/elepay"/></label><label>Description<input maxLength={255} value={description} onChange={e=>setDescription(e.target.value)}/></label><label>Environment<select value={env} onChange={e=>setEnv(e.target.value as "live"|"test")}><option value="live">Live</option><option value="test">Test endpoint / connectivity only</option></select></label><fieldset><legend>Events</legend>{EVENTS.map(v=><label className="choice" key={v}><input type="checkbox" checked={events.includes(v)} onChange={e=>setEvents(old=>e.target.checked?[...old,v]:old.filter(x=>x!==v))}/>{v}</label>)}</fieldset><button type="submit">Register endpoint</button></fieldset></form>}
  {confirm&&<div className="notice warn"><h3>{confirm.kind==="rotate"?"Rotate signing secret":confirm.endpoint.active?"Disable delivery":"Enable delivery"}</h3><p>{confirm.endpoint.url}</p><p>{confirm.kind==="rotate"?"Update the receiver secret immediately; the old secret stops working.":"This changes future event delivery."}</p><button disabled={action.busy} onClick={change}>Confirm change</button><button className="secondary" disabled={action.busy} onClick={()=>setConfirm(null)}>Cancel</button></div>}
  <button className="secondary" disabled={rows.loading||action.busy} onClick={()=>void rows.load()}>Refresh endpoints</button><div className="table-scroll" role="region" tabIndex={0} aria-label="Webhook endpoints"><table className="grid"><thead><tr><th>Endpoint</th><th>Environment</th><th>Events</th><th>State</th><th>Last delivery (GMT)</th><th>Actions</th></tr></thead><tbody>{(rows.data??[]).map(ep=><tr key={ep.id}><td className="small mono">{ep.url}<div className="muted">{ep.description}</div></td><td>{ep.environment}</td><td>{ep.eventTypes.join(", ")}</td><td>{ep.active?"Active":"Disabled"} · {ep.failureCount} failures</td><td>{date(ep.lastDeliveryAt)}</td><td><button className="secondary small" disabled={action.busy||!ep.active||!!secret} onClick={()=>ping(ep)}>Ping</button><button className="secondary small" disabled={action.busy||!!secret} onClick={()=>setConfirm({kind:"rotate",endpoint:ep})}>Rotate secret</button><button className="secondary small" disabled={action.busy||!!secret} onClick={()=>setConfirm({kind:"toggle",endpoint:ep})}>{ep.active?"Disable":"Enable"}</button></td></tr>)}{!rows.data?.length&&<Empty {...rows} colSpan={6} label="No webhook endpoints."/>}</tbody></table></div></section>;
}

function DeliveryLogsView({merchantId}:{merchantId:string}){
  const [filter,setFilter]=useState("all"),[query,setQuery]=useState(""),[inspect,setInspect]=useState<WebhookLog|null>(null),[confirm,setConfirm]=useState<WebhookLog|null>(null);
  const rows=useRows<WebhookLog>(base(merchantId)+"/webhooks/deliveries?limit=100"+(filter==="all"?"":"&status="+filter),deliveryMetadata),action=useAction();
  const found=(rows.data??[]).filter(l=>[l.eventId,l.eventType,l.deliveryId,l.requestUrl].some(v=>String(v??"").toLowerCase().includes(query.trim().toLowerCase())));
  function replay(){if(!confirm||!canReplayDelivery(confirm.status))return;const log=confirm;void action.run(async()=>{const r=await api<ReplayResult>(base(merchantId)+"/webhooks/deliveries/"+log.id+"/replay",{method:"POST",signal:timeout()});if(typeof r?.success!=="boolean"||!r.newLogId)throw new Error("Replay outcome not verified.");action.setMessage("Replay "+(r.success?"delivered":"failed")+" · HTTP "+r.statusCode+" · Log "+r.newLogId);setConfirm(null);setInspect(null);void rows.load();});}
  return <section className="panel"><h2>Delivery monitoring</h2><p className="muted small">Latest 100 records. A successful HTTP response is not evidence that a financial event was processed correctly by your application.</p><Feedback error={rows.error||action.error} message={action.message}/><div className="wallet-filter"><label>Status<select value={filter} onChange={e=>{setFilter(e.target.value);setInspect(null);setConfirm(null);}}>{["all","PENDING","DELIVERED","FAILED","RETRYING","DEAD"].map(v=><option key={v}>{v}</option>)}</select></label><label>Search event / delivery<input value={query} onChange={e=>setQuery(e.target.value)}/></label><button className="secondary" disabled={rows.loading||action.busy} onClick={()=>{setInspect(null);setConfirm(null);void rows.load();}}>Refresh</button></div>
  {confirm&&<div className="notice warn"><h3>Replay original event?</h3><p>Event {confirm.eventId}. The receiver may already have processed it. Confirm the receiver deduplicates this event ID.</p><button disabled={action.busy} onClick={replay}>Confirm replay</button><button className="secondary" disabled={action.busy} onClick={()=>setConfirm(null)}>Cancel</button></div>}
  {inspect&&<div className="receipt-detail"><h3>Delivery metadata</h3><dl><dt>Event</dt><dd>{inspect.eventType}</dd><dt>Event ID</dt><dd className="mono">{inspect.eventId}</dd><dt>Delivery ID</dt><dd className="mono">{inspect.deliveryId}</dd><dt>Endpoint</dt><dd>{inspect.requestUrl}</dd><dt>Attempt</dt><dd>{inspect.attemptNumber}</dd><dt>HTTP</dt><dd>{inspect.responseCode??"—"}</dd></dl><p className="muted small">Raw headers and payloads are not displayed here to reduce exposure of signatures and customer data.</p><button className="secondary" onClick={()=>setInspect(null)}>Close details</button></div>}
  <div className="table-scroll" tabIndex={0} role="region" aria-label="Delivery logs"><table className="grid"><thead><tr><th>Time (GMT)</th><th>Event</th><th>HTTP</th><th>Duration</th><th>Status</th><th>Attempt</th><th>Actions</th></tr></thead><tbody>{found.map(l=><tr key={l.id}><td>{date(l.createdAt)}</td><td>{l.eventType}<div className="small mono">{l.eventId}</div></td><td>{l.responseCode??"—"}</td><td>{l.durationMs} ms</td><td>{l.status}</td><td>{l.attemptNumber}{l.isManualRetry?" (manual)":""}</td><td><button className="secondary small" onClick={()=>setInspect(l)}>Inspect</button>{canReplayDelivery(l.status)&&<button className="secondary small" disabled={action.busy} onClick={()=>setConfirm(l)}>Replay</button>}</td></tr>)}{!found.length&&<Empty {...rows} colSpan={7} label="No matching delivery logs."/>}</tbody></table></div></section>;
}

function ApiExplorerView({merchantId}:{merchantId:string}){
  const [endpoint,setEndpoint]=useState("balances"),[reference,setReference]=useState(""),[result,setResult]=useState<string|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState<string|null>(null);
  const lock=useRef(false),seq=useRef(0);
  const path=explorerPath(merchantId,endpoint,reference);
  function reset(){seq.current++;setResult(null);setError(null);setBusy(false);}
  async function execute(){if(lock.current||!path)return;lock.current=true;const n=++seq.current;setBusy(true);setError(null);setResult(null);try{const r=await api<unknown>(path,{method:"GET",signal:timeout()});if(n===seq.current)setResult(JSON.stringify(r,null,2));}catch(e){if(n===seq.current)setError(msg(e));}finally{lock.current=false;if(n===seq.current)setBusy(false);}}
  return <section className="panel"><h2>Read-only API explorer</h2><p className="muted">Inspect your business using your authenticated session. No payments, refunds, payouts or checkout creation are executed from this explorer.</p><label>Endpoint<select disabled={busy} value={endpoint} onChange={e=>{reset();setEndpoint(e.target.value);}}><option value="balances">Merchant balances</option><option value="transactions">Recent transactions</option><option value="checkouts">Recent checkouts</option><option value="checkout">Checkout by reference</option></select></label>{endpoint==="checkout"&&<label>Checkout reference<input disabled={busy} value={reference} maxLength={120} onChange={e=>{reset();setReference(e.target.value);}}/></label>}<p className="mono small">GET {path??"(enter a valid reference)"}</p><button disabled={busy||!path} onClick={()=>void execute()}>{busy?"Loading…":"Run GET request"}</button><Feedback error={error}/>{result!==null&&<><h3>Server response</h3><p className="small muted">May contain confidential business information.</p><pre className="code-box">{result}</pre><button className="secondary" onClick={()=>setResult(null)}>Clear response</button></>}</section>;
}

export function canonicalRequestV2(fields:string[]):string {return "elepay-request:v2"+fields.map(v=>":"+new TextEncoder().encode(v).length+":"+v).join("");}
function SdkSnippetsView({merchantId}:{merchantId:string}){
  const [language,setLanguage]=useState("node"),[feedback,setFeedback]=useState("");
  const node=[
    'import { createHmac, randomUUID } from "node:crypto";',
    'const base = process.env.ELEPAY_BASE_URL;',
    'const apiKey = process.env.ELEPAY_API_KEY;',
    'const secret = process.env.ELEPAY_SIGNING_SECRET;',
    'const orderKey = process.env.ELEPAY_ORDER_IDEMPOTENCY_KEY; // persist per logical order',
    'if (!base || !apiKey || !secret || !orderKey) throw Error("Missing configuration");',
    'const url = new URL("/v1/merchants/'+merchantId+'/checkouts", base);',
    'if (url.protocol !== "https:" || url.username || url.password) throw Error("HTTPS origin required");',
    'const body = JSON.stringify({ amount: 2500, description: "Order payment" });',
    'const ts = String(Math.floor(Date.now() / 1000));',
    'const nonce = randomUUID();',
    'const fields = [ts, "POST", url.pathname, url.search.slice(1), nonce, orderKey, body];',
    'const canonical = "elepay-request:v2" + fields.map(v => ":" + Buffer.byteLength(v,"utf8") + ":" + v).join("");',
    'const signature = createHmac("sha256", secret).update(canonical).digest("hex");',
    'const r = await fetch(url, { method:"POST", redirect:"error", body, headers:{',
    '  "Content-Type":"application/json", "Accept":"application/json", "X-Elepay-Api-Key":apiKey,',
    '  "Idempotency-Key":orderKey, "X-Elepay-Timestamp":ts, "X-Elepay-Nonce":nonce,',
    '  "X-Elepay-Signature-Version":"2", "X-Elepay-Signature":signature }});',
    'if (!r.ok) throw Error("Request rejected: " + r.status);',
    'const checkout = await r.json(); // checkout creation is not payment confirmation',
  ].join("\n");
  const py=[
    'import os, json, time, uuid, hmac, hashlib, urllib.parse, requests',
    'base = os.environ["ELEPAY_BASE_URL"].rstrip("/")',
    'path = "/v1/merchants/'+merchantId+'/checkouts"',
    'url = base + path',
    'origin = urllib.parse.urlsplit(base)',
    'if origin.scheme != "https" or origin.username or origin.password or origin.path or origin.query or origin.fragment: raise ValueError("HTTPS origin required")',
    'key = os.environ["ELEPAY_ORDER_IDEMPOTENCY_KEY"]  # persist per logical order',
    'body = json.dumps({"amount":2500,"description":"Order payment"}, separators=(",",":")).encode()',
    'ts, nonce = str(int(time.time())), str(uuid.uuid4())',
    'fields = [ts.encode(), b"POST", path.encode(), b"", nonce.encode(), key.encode(), body]',
    'canonical = b"elepay-request:v2" + b"".join(b":" + str(len(v)).encode() + b":" + v for v in fields)',
    'signature = hmac.new(os.environ["ELEPAY_SIGNING_SECRET"].encode(), canonical, hashlib.sha256).hexdigest()',
    'r = requests.post(url, data=body, timeout=20, allow_redirects=False, headers={',
    '  "Content-Type":"application/json","Accept":"application/json","X-Elepay-Api-Key":os.environ["ELEPAY_API_KEY"],',
    '  "Idempotency-Key":key,"X-Elepay-Timestamp":ts,"X-Elepay-Nonce":nonce,',
    '  "X-Elepay-Signature-Version":"2","X-Elepay-Signature":signature})',
    'if not 200 <= r.status_code < 300: raise RuntimeError("Request rejected: "+str(r.status_code))',
    'checkout = r.json()  # not a paid receipt',
  ].join("\n");
  const code=language==="node"?node:py;
  return <section className="panel"><h2>Server-side integration examples</h2><p>These examples use the actual merchant checkout route and HMAC-SHA256 v2 request contract. They are not executed here. Use a separately configured test deployment before real payments.</p><label>Language<select value={language} onChange={e=>{setLanguage(e.target.value);setFeedback("");}}><option value="node">Node.js (native fetch)</option><option value="python">Python (requests)</option></select></label><pre className="code-box">{code}</pre><button className="secondary" onClick={()=>void navigator.clipboard.writeText(code).then(()=>setFeedback("Copied example.")).catch(()=>setFeedback("Copy failed."))}>Copy example</button><p role="status">{feedback}</p><h3>Verification checklist</h3><p>Amounts are integer pesewas. Persist the same idempotency key and body for a logical request, generate a new nonce and timestamp on retry, and verify the merchant-scoped checkout status before fulfilling an order. Verify webhook HMAC over timestamp + "." + raw body; reject stale timestamps, compare signatures in constant time and deduplicate event IDs.</p><p>Use the repository OpenAPI contract at <code>docs/openapi.json</code> for other routes. The examples do not depend on an unverified SDK package.</p></section>;
}
function WooCommerceView({merchantId}:{merchantId:string}){
  return <section className="panel"><h2>WooCommerce payment gateway</h2><p>ELEPAY's existing WordPress integration remains supported in the source tree. Payment method availability is determined by the configured backend rails and merchant permissions.</p><h3>Installation and configuration</h3><ol><li>Install the repository folder <code>plugins/woocommerce-elepay/</code> into WordPress plugins and activate it.</li><li>Open WooCommerce → Settings → Payments → ELEPAY.</li><li>Set the approved HTTPS ELEPAY API base URL for the correct deployment and enter merchant UUID <code className="mono">{merchantId}</code>.</li><li>Configure the server API key and corresponding request-signing secret, then register the webhook URL shown in WooCommerce and set its signing secret.</li><li>Verify payment status, duplicate notifications, refunds and order reconciliation in the isolated test deployment before activation.</li></ol><p className="notice warn">Do not mix test and live credentials. This page does not certify that a particular payment rail, WordPress version or production plugin installation has been independently verified.</p></section>;
}
