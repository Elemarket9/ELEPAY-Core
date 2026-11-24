import { Component, lazy, StrictMode, Suspense, useEffect, useRef, useState, type ReactNode } from "react";
import { createRoot } from "react-dom/client";
const Checkout = lazy(()=>import("./Checkout").then(m=>({default:m.Checkout})));
const StartFromLink = lazy(()=>import("./Checkout").then(m=>({default:m.StartFromLink})));
import { completeSignIn, devSignInEnabled, getToken, oidcEnabled, SESSION_EXPIRED, signIn, signOut } from "./auth";

// Dashboards load on demand so the checkout page stays small on slow connections.
const MerchantDashboard = lazy(() => import("./Merchant").then((m) => ({ default: m.MerchantDashboard })));
const OpsConsole = lazy(() => import("./Ops").then((m) => ({ default: m.OpsConsole })));
const Wallet = lazy(() => import("./Wallet").then((m) => ({ default: m.Wallet })));
const Kitchen = lazy(() => import("./Kitchen").then((m) => ({ default: m.Kitchen })));
const DeveloperPortal = lazy(() => import("./Developer").then((m) => ({ default: m.DeveloperPortal })));
import "./styles.css";

function Loading({ text = "Loading your workspace…" }: { text?: string }) {
  return <main className="narrow"><div className="panel loading-state" role="status"><span className="spinner" aria-hidden="true" />{text}</div></main>;
}

class AppBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    if (this.state.failed) return <main className="narrow"><div className="panel"><h1>Unable to display this page</h1><p>Your payment status has not been changed by this display error. Reopen the same payment reference to check its status.</p><button onClick={() => window.location.reload()}>Reload page</button><a className="text-link" href="/">Return home</a></div></main>;
    return this.props.children;
  }
}

function SignedIn({ children, roles = [] }: { children: React.ReactNode; roles?: string[] }) {
  const [ready, setReady] = useState<boolean | null>(null);
  const [subject, setSubject] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const signInLock=useRef(false);
  useEffect(()=>{
    let alive=true;let generation=0;
    const verify=()=>{const n=++generation;void getToken().then(t=>{if(alive&&n===generation)setReady(Boolean(t));}).catch(()=>{if(alive&&n===generation){setError("Session could not be verified.");setReady(false);}});};
    const expire=()=>{generation++;setReady(false);setError("Your session ended. Sign in again to continue.");};
    const resume=()=>{if(document.visibilityState==="visible")verify();};
    verify();const timer=setInterval(resume,30000);window.addEventListener(SESSION_EXPIRED,expire);document.addEventListener("visibilitychange",resume);
    return()=>{alive=false;clearInterval(timer);window.removeEventListener(SESSION_EXPIRED,expire);document.removeEventListener("visibilitychange",resume);};
  },[]);

  if (ready === null) return <Loading text="Checking your session…" />;
  if (!ready) {
    return (
      <main className="narrow auth-page">
        <div className="panel auth-panel"><p className="eyebrow">ELEPAY ACCOUNT</p><h1>Sign in</h1><p className="muted">Access your ELEPAY workspace.</p>
        {error && <p className="error" role="alert">{error}</p>}
        {oidcEnabled ? (
          <button
            disabled={busy}
            onClick={() => {
              if(signInLock.current)return;signInLock.current=true;
              setError(null);
              setBusy(true);
              void signIn()
                .catch((e) => setError(e instanceof Error ? e.message : String(e)))
                .finally(() => {signInLock.current=false;setBusy(false);});
            }}
          >
            {busy ? "Signing in…" : "Sign in with ELEPAY"}
          </button>
        ) : devSignInEnabled ? (
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              setError(null);
              setBusy(true);
              try {
                await signIn(subject, roles);
                setReady(true);
              } catch (err) {
                setError(err instanceof Error ? err.message : String(err));
              } finally {
                setBusy(false);
              }
            }}
          >
            <p className="muted small">Development sign-in (dev and test profiles only).</p>
            <label>Subject<input value={subject} onChange={(e) => setSubject(e.target.value)} required /></label>
            <button type="submit" disabled={busy}>{busy ? "Signing in…" : "Continue"}</button>
          </form>
        ) : <p className="notice warn" role="alert">Sign-in is temporarily unavailable. Please contact ELEPAY support.</p>}
        </div>
      </main>
    );
  }
  return <Suspense fallback={<Loading />}>{children}</Suspense>;
}

function SessionAction() {
  const [signedIn, setSignedIn] = useState(false);
  const [error, setError] = useState(false);
  useEffect(() => { void getToken().then(t => setSignedIn(Boolean(t))).catch(() => setSignedIn(false)); }, []);
  return signedIn ? <div><button className="link" onClick={() => { setError(false); void signOut().then(() => window.location.assign("/")).catch(() => setError(true)); }}>Sign out</button>{error && <span role="alert">Sign-out unavailable. Try again.</span>}</div> : <a className="header-action" href="/app">Sign in</a>;
}

function App() {
  const path = window.location.pathname;
  const [callbackError, setCallbackError] = useState<string | null>(null);
  useEffect(() => {
    if (path === "/app/callback") {
      completeSignIn()
        .then((to) => {
          window.location.replace(to);
        })
        .catch((err) => {
          setCallbackError(err instanceof Error ? err.message : String(err));
        });
    }
  }, [path]);
  if (path === "/app/callback" && callbackError) {
    return (
      <main className="narrow">
        <h1>Sign-in failed</h1>
        <p className="error">{callbackError}</p>
        <a href="/">Return home</a>
      </main>
    );
  }
  if (path === "/app/callback") return <Loading text="Completing secure sign-in…" />;

  let m: RegExpMatchArray | null;
  if ((m = path.match(/^\/pay\/l\/([\w-]+)$/))) return <Suspense fallback={<Loading text="Loading payment link…" />}><StartFromLink slug={m[1]} /></Suspense>;
  if ((m = path.match(/^\/pay\/m\/(\d{6})$/))) return <Suspense fallback={<Loading text="Loading merchant…" />}><StartFromLink code={m[1]} /></Suspense>;
  if ((m = path.match(/^\/pay\/([\w-]+)$/))) return <Suspense fallback={<Loading text="Loading checkout…" />}><Checkout reference={m[1]} /></Suspense>;

  // Kitchen screens run full-screen on tablets, without the site header.
  if (path.startsWith("/kitchen")) return <Suspense fallback={<Loading text="Loading kitchen display…" />}><Kitchen /></Suspense>;

  const links = [["/app", "Personal"], ["/merchant", "Business"], ["/developer", "Developers"], ["/ops", "Operations"]];
  const header = (
    <header className="site-header"><a className="skip-link" href="#main-content">Skip to content</a>
      <a href="/" className="brand" aria-label="ELEPAY home"><span className="brand-mark" aria-hidden="true">E</span>ELEPAY</a>
      <nav aria-label="Main navigation">{links.map(([url, label]) => <a key={url} href={url} aria-current={path.startsWith(url) ? "page" : undefined}>{label}</a>)}</nav>
      <SessionAction />
    </header>
  );
  if (path==="/merchant"||path.startsWith("/merchant/")) return <>{header}<div id="main-content" tabIndex={-1}><SignedIn><MerchantDashboard /></SignedIn></div></>;
  if (path==="/developer"||path.startsWith("/developer/")) return <>{header}<div id="main-content" tabIndex={-1}><SignedIn><DeveloperPortal /></SignedIn></div></>;
  if (path==="/ops"||path.startsWith("/ops/")) return <>{header}<div id="main-content" tabIndex={-1}><SignedIn roles={["ops"]}><OpsConsole /></SignedIn></div></>;
  if (path==="/app"||path.startsWith("/app/")) return <>{header}<div id="main-content" tabIndex={-1}><SignedIn><Wallet /></SignedIn></div></>;
  if(path!=="/")return <>{header}<main id="main-content" className="narrow"><h1>Page not found</h1><p>Check the address or return to ELEPAY.</p><a href="/">Return home</a></main></>;
  return (
    <>{header}
      <main className="home" id="main-content">
        <section className="home-hero">
          <div className="hero-copy"><p className="eyebrow">GHANA-FIRST FINANCIAL TECHNOLOGY</p><h1>Payments for everyday life and business.</h1><p>Manage your wallet, collect payments and connect your business to ELEPAY.</p><div className="hero-actions"><a className="action-link" href="/app">Open personal wallet</a><a className="action-link outline" href="/merchant">Business dashboard</a></div></div>
          <form className="panel merchant-pay" onSubmit={(e) => { e.preventDefault(); const code = String(new FormData(e.currentTarget).get("code") ?? "").trim(); if (/^\d{6}$/.test(code)) window.location.assign(`/pay/m/${code}`); }}>
            <p className="eyebrow">PAY A MERCHANT</p><h2>Enter merchant code</h2><p className="muted">Review the merchant and payment amount before approving.</p>
            <label>Six-digit merchant code<input name="code" inputMode="numeric" pattern="\d{6}" maxLength={6} autoComplete="off" placeholder="000000" required /></label>
            <button type="submit">Continue to payment</button>
          </form>
        </section>
        <section className="workspace-grid" aria-label="ELEPAY workspaces">
          <a className="panel workspace-card" href="/app"><p className="eyebrow">PERSONAL</p><h2>Your wallet</h2><p className="muted">View balances, send money and review transaction history.</p><span>Open wallet →</span></a>
          <a className="panel workspace-card" href="/merchant"><p className="eyebrow">BUSINESS</p><h2>Merchant tools</h2><p className="muted">Manage checkouts, payments and business operations.</p><span>Open dashboard →</span></a>
          <a className="panel workspace-card" href="/developer"><p className="eyebrow">DEVELOPERS</p><h2>Payment integration</h2><p className="muted">Manage API credentials and webhook integrations.</p><span>Open developer portal →</span></a>
        </section>
        <footer className="site-footer">ELEPAY <span>Ghana · Personal and business payments</span></footer>
      </main>
    </>
  );
}

createRoot(document.getElementById("root")!).render(<StrictMode><AppBoundary><App /></AppBoundary></StrictMode>);
