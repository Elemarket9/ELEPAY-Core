import { lazy, StrictMode, Suspense, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { Checkout, StartFromLink } from "./Checkout";
import { completeSignIn, getToken, oidcEnabled, signIn, signOut } from "./auth";

// Dashboards load on demand so the checkout page stays small on slow connections.
const MerchantDashboard = lazy(() => import("./Merchant").then((m) => ({ default: m.MerchantDashboard })));
const OpsConsole = lazy(() => import("./Ops").then((m) => ({ default: m.OpsConsole })));
const Wallet = lazy(() => import("./Wallet").then((m) => ({ default: m.Wallet })));
const Kitchen = lazy(() => import("./Kitchen").then((m) => ({ default: m.Kitchen })));
const DeveloperPortal = lazy(() => import("./Developer").then((m) => ({ default: m.DeveloperPortal })));
import "./styles.css";

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

  if (ready === null) return null;
  if (!ready) {
    return (
      <main className="narrow">
        <h1>Sign in</h1>
        {error && <p className="error">{error}</p>}
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
        ) : (
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
        )}
      </main>
    );
  }
  return <Suspense fallback={<p>Loading…</p>}>{children}</Suspense>;
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
      <main className="narrow">
        <h1>Sign-in failed</h1>
        <p className="error">{callbackError}</p>
        <a href="/">Return home</a>
      </main>
    );
  }
  if (path === "/app/callback" && !callbackDone) return <p>Signing in…</p>;

  let m: RegExpMatchArray | null;
  if ((m = path.match(/^\/pay\/l\/([\w-]+)$/))) return <StartFromLink slug={m[1]} />;
  if ((m = path.match(/^\/pay\/m\/(\d{6})$/))) return <StartFromLink code={m[1]} />;
  if ((m = path.match(/^\/pay\/([\w-]+)$/))) return <Checkout reference={m[1]} />;

  // Kitchen screens run full-screen on tablets, without the site header.
  if (path.startsWith("/kitchen")) return <Suspense fallback={null}><Kitchen /></Suspense>;

  const header = (
    <header>
      <a href="/" className="brand">ELEPAY</a>
      <nav><a href="/app">Wallet</a><a href="/merchant">Merchant</a><a href="/developer">Developer</a><a href="/ops">Operations</a>
        <button className="link" onClick={() => void signOut().then(() => window.location.assign("/"))}>Sign out</button></nav>
    </header>
  );
  if (path.startsWith("/merchant")) return <>{header}<SignedIn><MerchantDashboard /></SignedIn></>;
  if (path.startsWith("/developer")) return <>{header}<SignedIn><DeveloperPortal /></SignedIn></>;
  if (path.startsWith("/ops")) return <>{header}<SignedIn roles={["ops"]}><OpsConsole /></SignedIn></>;
  if (path.startsWith("/app")) return <>{header}<SignedIn><Wallet /></SignedIn></>;
  return (
    <>{header}
      <main className="narrow">
        <h1>ELEPAY</h1>
        <p>Wallets, payments and payouts for Ghana. Pay a merchant with a link, QR or merchant code.</p>
        <form onSubmit={(e) => { e.preventDefault(); const code = new FormData(e.currentTarget).get("code"); window.location.assign(`/pay/m/${code}`); }}>
          <label>Merchant code<input name="code" inputMode="numeric" pattern="\d{6}" required /></label>
          <button type="submit">Pay</button>
        </form>
      </main>
    </>
  );
}

createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>);
