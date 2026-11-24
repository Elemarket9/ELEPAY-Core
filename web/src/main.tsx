import { Component, lazy, StrictMode, Suspense, useEffect, useState, type ReactNode } from "react";
import { createRoot } from "react-dom/client";
import { Checkout, StartFromLink } from "./Checkout";
import { completeSignIn, devSignInEnabled, getToken, oidcEnabled, signIn, signOut } from "./auth";

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

  useEffect(() => {
    getToken()
      .then((t) => setReady(!!t))
      .catch((e) => {
        setError(e instanceof Error ? e.message : String(e));
        setReady(false);
      });
  }, []);

  if (ready === null) return <Loading text="Checking your session…" />;
  if (!ready) {
    return (
      <main className="narrow auth-page" id="main-content">
        <div className="panel auth-panel"><p className="eyebrow">ELEPAY ACCOUNT</p><h1>Sign in</h1><p className="muted">Access your ELEPAY workspace.</p>
        {error && <p className="error" role="alert">{error}</p>}
        {oidcEnabled ? (
          <button
            disabled={busy}
            onClick={() => {
              setError(null);
              setBusy(true);
              void signIn()
                .catch((e) => setError(e instanceof Error ? e.message : String(e)))
                .finally(() => setBusy(false));
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
  if ((m = path.match(/^\/pay\/l\/([\w-]+)$/))) return <StartFromLink slug={m[1]} />;
  if ((m = path.match(/^\/pay\/m\/(\d{6})$/))) return <StartFromLink code={m[1]} />;
  if ((m = path.match(/^\/pay\/([\w-]+)$/))) return <Checkout reference={m[1]} />;

  // Kitchen screens run full-screen on tablets, without the site header.
  if (path.startsWith("/kitchen")) return <Suspense fallback={<Loading text="Loading kitchen display…" />}><Kitchen /></Suspense>;

  const links = [["/app", "Personal"], ["/merchant", "Business"], ["/developer", "Developers"], ["/ops", "Operations"]];
  const header = (
    <header className="site-header">
      <a href="/" className="brand" aria-label="ELEPAY home"><span className="brand-mark" aria-hidden="true">E</span>ELEPAY</a>
      <nav aria-label="Main navigation">{links.map(([url, label]) => <a key={url} href={url} aria-current={path.startsWith(url) ? "page" : undefined}>{label}</a>)}</nav>
      <SessionAction />
    </header>
  );
  if (path.startsWith("/merchant")) return <>{header}<SignedIn><MerchantDashboard /></SignedIn></>;
  if (path.startsWith("/developer")) return <>{header}<SignedIn><DeveloperPortal /></SignedIn></>;
  if (path.startsWith("/ops")) return <>{header}<SignedIn roles={["ops"]}><OpsConsole /></SignedIn></>;
  if (path.startsWith("/app")) return <>{header}<SignedIn><Wallet /></SignedIn></>;
  return (
    <>{header}
      <main className="home" id="main-content">
        <section className="home-hero">
          <div className="hero-copy"><p className="eyebrow">GHANA-FIRST FINANCIAL TECHNOLOGY</p><h1>Payments for everyday life and business.</h1><p>Manage your wallet, collect payments and connect your business to ELEPAY.</p><div className="hero-actions"><a className="action-link" href="/app">Open personal wallet</a><a className="action-link outline" href="/merchant">Business dashboard</a></div></div>
          <form className="panel merchant-pay" onSubmit={(e) => { e.preventDefault(); const code = String(new FormData(e.currentTarget).get("code") ?? "").trim(); if (/^\\d{6}$/.test(code)) window.location.assign(`/pay/m/${code}`); }}>
            <p className="eyebrow">PAY A MERCHANT</p><h2>Enter merchant code</h2><p className="muted">Review the merchant and payment amount before approving.</p>
            <label>Six-digit merchant code<input name="code" inputMode="numeric" pattern="\\d{6}" maxLength={6} autoComplete="off" placeholder="000000" required /></label>
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
