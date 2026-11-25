import { lazy, StrictMode, Suspense, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { Checkout, StartFromLink } from "./Checkout";
import { completeSignIn, getToken, signOut } from "./auth";
import { LoginScreen } from "./LoginScreen";
import {
  BrandMark,
  IconActivity,
  IconChefHat,
  IconKey,
  IconMonitor,
  IconQrCode,
  IconWallet,
} from "./ui/Icons";

// Dashboards load on demand so the checkout page stays small on slow connections.
const MerchantDashboard = lazy(() => import("./Merchant").then((m) => ({ default: m.MerchantDashboard })));
const OpsConsole = lazy(() => import("./Ops").then((m) => ({ default: m.OpsConsole })));
const Wallet = lazy(() => import("./Wallet").then((m) => ({ default: m.Wallet })));
const Kitchen = lazy(() => import("./Kitchen").then((m) => ({ default: m.Kitchen })));
const DeveloperPortal = lazy(() => import("./Developer").then((m) => ({ default: m.DeveloperPortal })));
import "./styles.css";

function SignedIn({ children, roles = [] }: { children: React.ReactNode; roles?: string[] }) {
  const [ready, setReady] = useState<boolean | null>(null);

  useEffect(() => {
    getToken()
      .then((t) => setReady(!!t))
      .catch(() => setReady(false));
  }, []);

  if (ready === null) return null;
  if (!ready) {
    return <LoginScreen roles={roles} onAuthenticated={() => setReady(true)} />;
  }
  return (
    <Suspense fallback={<main className="narrow"><p className="muted">Loading workspace…</p></main>}>
      {children}
    </Suspense>
  );
}

function App() {
  const path = window.location.pathname;
  const [callbackDone, setCallbackDone] = useState(false);
  const [callbackError, setCallbackError] = useState<string | null>(null);
  useEffect(() => {
    if (path === "/app/callback") {
      completeSignIn()
        .then((to) => {
          window.history.replaceState(null, "", to);
          setCallbackDone(true);
        })
        .catch((err) => {
          setCallbackError(err instanceof Error ? err.message : String(err));
        });
    }
  }, [path]);
  if (path === "/app/callback" && callbackError) {
    return (
      <div className="auth-shell">
        <div className="auth-card">
          <h1>Sign-in failed</h1>
          <p className="notice error">{callbackError}</p>
          <a href="/">Return home</a>
        </div>
      </div>
    );
  }
  if (path === "/app/callback" && !callbackDone) {
    return (
      <div className="auth-shell">
        <div className="auth-card">
          <p className="muted">Completing secure sign-in…</p>
        </div>
      </div>
    );
  }

  let m: RegExpMatchArray | null;
  if ((m = path.match(/^\/pay\/l\/([\w-]+)$/))) return <StartFromLink slug={m[1]} />;
  if ((m = path.match(/^\/pay\/m\/(\d{6})$/))) return <StartFromLink code={m[1]} />;
  if ((m = path.match(/^\/pay\/([\w-]+)$/))) return <Checkout reference={m[1]} />;

  // Kitchen screens run full-screen on tablets, without the site header.
  if (path.startsWith("/kitchen")) return <Suspense fallback={null}><Kitchen /></Suspense>;

  const navItemClass = (prefix: string) => (path.startsWith(prefix) ? "nav-active" : undefined);

  const header = (
    <header className="app-header">
      <a href="/" className="brand">
        <BrandMark size={26} />
        <span>ELEPAY</span>
        <span className="brand-sub">GH</span>
      </a>
      <nav aria-label="Primary">
        <a href="/app" className={navItemClass("/app")}>Wallet</a>
        <a href="/merchant" className={navItemClass("/merchant")}>Merchant</a>
        <a href="/kitchen" className={navItemClass("/kitchen")}>Kitchen</a>
        <a href="/developer" className={navItemClass("/developer")}>Developer</a>
        <a href="/ops" className={navItemClass("/ops")}>Operations</a>
        <button
          className="link nav-signout"
          onClick={() => void signOut().then(() => window.location.assign("/"))}
        >
          Sign out
        </button>
      </nav>
    </header>
  );
  if (path.startsWith("/merchant")) return <>{header}<SignedIn><MerchantDashboard /></SignedIn></>;
  if (path.startsWith("/developer")) return <>{header}<SignedIn><DeveloperPortal /></SignedIn></>;
  if (path.startsWith("/ops")) return <>{header}<SignedIn roles={["ops"]}><OpsConsole /></SignedIn></>;
  if (path.startsWith("/app")) return <>{header}<SignedIn><Wallet /></SignedIn></>;
  return (
    <>
      {header}
      <div className="auth-shell">
        <div className="auth-card">
          <div className="auth-brand-row">
            <BrandMark size={32} />
            <div>
              <h1 style={{ fontSize: "1.35rem", margin: 0 }}>Pay a Merchant</h1>
              <p className="small muted" style={{ margin: 0 }}>Wallets, payments and payouts for Ghana</p>
            </div>
          </div>
          <p className="small muted">
            Enter a 6-digit ELEPAY merchant code to pay directly with MoMo, Card, GhQR, or your ELEPAY Wallet.
          </p>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const code = new FormData(e.currentTarget).get("code");
              window.location.assign(`/pay/m/${code}`);
            }}
          >
            <label>
              Merchant code
              <input
                name="code"
                className="mono"
                inputMode="numeric"
                pattern="\d{6}"
                maxLength={6}
                placeholder="123456"
                required
              />
            </label>
            <button type="submit" className="large full-width" style={{ marginTop: "0.5rem" }}>
              <IconQrCode size={17} />
              Continue to Pay
            </button>
          </form>

          <div className="portal-links-grid">
            <a href="/merchant" className="portal-link-tile">
              <IconMonitor size={16} /> Merchant &amp; POS
            </a>
            <a href="/kitchen" className="portal-link-tile">
              <IconChefHat size={16} /> Kitchen Display
            </a>
            <a href="/app" className="portal-link-tile">
              <IconWallet size={16} /> Customer Wallet
            </a>
            <a href="/ops" className="portal-link-tile">
              <IconActivity size={16} /> Ops Console
            </a>
            <a href="/developer" className="portal-link-tile" style={{ gridColumn: "1 / -1" }}>
              <IconKey size={16} /> Developer API &amp; Webhooks
            </a>
          </div>
        </div>
      </div>
    </>
  );
}

createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>);
