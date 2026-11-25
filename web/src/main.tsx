import { lazy, StrictMode, Suspense, useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { Checkout, StartFromLink } from "./Checkout";
import { api } from "./api";
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

interface CallerProfile {
  subject: string;
  roles: string[];
  primaryRole: string;
  isOps: boolean;
  isOpsAdmin: boolean;
}

const OPS_ROLES = new Set([
  "ops",
  "ops_admin",
  "support_agent",
  "onboarding_officer",
  "finance_ops",
  "risk_compliance",
]);

function tokenHasAnyRole(token: string, required: string[], profile?: CallerProfile | null): boolean {
  if (required.length === 0) return true;
  if (profile) {
    if (required.includes("ops") && profile.isOps) return true;
    return required.some((r) => profile.roles.includes(r));
  }
  try {
    const parts = token.split(".");
    if (parts.length !== 3) return true;
    const base64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
    const payload = JSON.parse(atob(padded)) as { roles?: string[]; realm_access?: { roles?: string[] } };
    const roles = payload.roles ?? payload.realm_access?.roles ?? [];
    return required.some((r) => roles.includes(r) || (r === "ops" && roles.some((role) => OPS_ROLES.has(role))));
  } catch {
    return true;
  }
}

function SignedIn({
  children,
  roles = [],
  onAuthChange,
}: {
  children: React.ReactNode;
  roles?: string[];
  onAuthChange?: (profile: CallerProfile | null) => void;
}) {
  const [token, setToken] = useState<string | null | undefined>(undefined);
  const [profile, setProfile] = useState<CallerProfile | null>(null);
  const [profileLoaded, setProfileLoaded] = useState(false);

  const loadSession = () => {
    setProfileLoaded(false);
    getToken()
      .then(async (t) => {
        if (!t) {
          setToken(null);
          setProfile(null);
          setProfileLoaded(true);
          onAuthChange?.(null);
          return;
        }
        try {
          const me = await api<CallerProfile>("/v1/auth/me");
          setToken(t);
          setProfile(me);
          onAuthChange?.(me);
        } catch {
          // If token was rejected (401), api.ts clears stale storage; re-check token.
          const remaining = await getToken();
          setToken(remaining);
          setProfile(null);
          onAuthChange?.(remaining ? { subject: "authenticated", roles: [], primaryRole: "user", isOps: false, isOpsAdmin: false } : null);
        } finally {
          setProfileLoaded(true);
        }
      })
      .catch(() => {
        setToken(null);
        setProfile(null);
        setProfileLoaded(true);
        onAuthChange?.(null);
      });
  };

  useEffect(() => {
    loadSession();
  }, []);

  if (token === undefined || !profileLoaded) return null;
  if (!token) {
    return <LoginScreen roles={roles} onAuthenticated={loadSession} />;
  }
  if (!tokenHasAnyRole(token, roles, profile)) {
    return (
      <div className="auth-shell">
        <div className="auth-card">
          <h1>Operations Access Required</h1>
          <p className="notice error">
            Signed in as <strong>{profile?.subject ?? "current user"}</strong> ({profile?.primaryRole ?? "customer"}),
            which does not have an operations role (<code>ops</code>, <code>ops_admin</code>, <code>support_agent</code>,
            <code>onboarding_officer</code>, <code>finance_ops</code>, or <code>risk_compliance</code>).
          </p>
          <button
            type="button"
            className="large full-width"
            onClick={() => void signOut().then(() => window.location.assign("/"))}
          >
            Sign out &amp; switch account
          </button>
        </div>
      </div>
    );
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
  const [sessionProfile, setSessionProfile] = useState<CallerProfile | null>(null);

  useEffect(() => {
    void getToken().then(async (t) => {
      if (!t) {
        setSessionProfile(null);
        return;
      }
      try {
        setSessionProfile(await api<CallerProfile>("/v1/auth/me"));
      } catch {
        setSessionProfile(null);
      }
    }).catch(() => setSessionProfile(null));
  }, [callbackDone]);

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
        {sessionProfile && (
          <span className="small" style={{ opacity: 0.85, padding: "0 0.4rem" }}>
            {sessionProfile.subject} ({sessionProfile.primaryRole})
          </span>
        )}
        {sessionProfile && (
          <button
            className="link nav-signout"
            onClick={() => void signOut().then(() => window.location.assign("/"))}
          >
            Sign out
          </button>
        )}
      </nav>
    </header>
  );
  if (path.startsWith("/merchant")) return <>{header}<SignedIn onAuthChange={setSessionProfile}><MerchantDashboard /></SignedIn></>;
  if (path.startsWith("/developer")) return <>{header}<SignedIn onAuthChange={setSessionProfile}><DeveloperPortal /></SignedIn></>;
  if (path.startsWith("/ops")) return <>{header}<SignedIn roles={["ops"]} onAuthChange={setSessionProfile}><OpsConsole /></SignedIn></>;
  if (path.startsWith("/app")) return <>{header}<SignedIn onAuthChange={setSessionProfile}><Wallet /></SignedIn></>;
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
