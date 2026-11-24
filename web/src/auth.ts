import type { UserManager } from "oidc-client-ts";

/**
 * Production: OpenID Connect authorization code flow with PKCE against the
 * configured issuer. Development: tokens from the service's /dev/token
 * endpoint (only present in dev/test profiles).
 */
const issuer = import.meta.env.VITE_OIDC_ISSUER as string | undefined;
const clientId = import.meta.env.VITE_OIDC_CLIENT_ID as string | undefined;

let managerPromise: Promise<UserManager> | null = null;

/** The OIDC client is loaded only when sign-in is configured and needed, keeping checkout pages small. */
function oidc(): Promise<UserManager> | null {
  if (!issuer || !clientId) return null;
  managerPromise ??= import("oidc-client-ts").then(({ UserManager, WebStorageStateStore }) => new UserManager({
    authority: issuer,
    client_id: clientId,
    redirect_uri: window.location.origin + "/app/callback",
    post_logout_redirect_uri: window.location.origin,
    response_type: "code",
    scope: "openid profile",
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  }));
  return managerPromise;
}

const DEV_KEY = "elepay.devToken";

export const oidcEnabled = Boolean(issuer && clientId);
// Production bundles must never fall back to development token issuance.
export const devSignInEnabled = import.meta.env.DEV && !oidcEnabled;

export function safeReturnPath(state: unknown): string {
  if (typeof state !== "string" || !state.startsWith("/") || state.startsWith("//") || /[\\\u0000-\u001f\u007f]/.test(state)) return "/";
  const base = "https://elepay.invalid";
  try {
    const url = new URL(state, base);
    return url.origin === base && url.pathname !== "/app/callback" ? url.pathname + url.search + url.hash : "/";
  } catch { return "/"; }
}

export async function getToken(): Promise<string | null> {
  const pending = oidc();
  if (pending) {
    const user = await (await pending).getUser();
    return user && !user.expired ? user.access_token : null;
  }
  return devSignInEnabled ? sessionStorage.getItem(DEV_KEY) : null;
}

export async function signIn(devSubject?: string, roles: string[] = []): Promise<void> {
  const pending = oidc();
  if (pending) {
    await (await pending).signinRedirect({ state: safeReturnPath(window.location.pathname + window.location.search) });
    return;
  }
  if (!devSignInEnabled) throw new Error("Sign-in is not configured. Contact ELEPAY support.");
  const response = await fetch("/dev/token", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ subject: devSubject, roles }),
  });
  if (!response.ok) throw new Error("Development sign-in is not available on this server");
  sessionStorage.setItem(DEV_KEY, (await response.json()).accessToken);
}

let callbackPromise: Promise<string> | null = null;
export function completeSignIn(): Promise<string> {
  // React StrictMode may mount the callback twice; consume the OIDC state once.
  return callbackPromise ??= (async () => {
    const pending = oidc();
    if (!pending) throw new Error("Sign-in is not configured.");
    const user = await (await pending).signinRedirectCallback();
    return safeReturnPath(user.state);
  })();
}

export async function signOut(): Promise<void> {
  const pending = oidc();
  if (pending) await (await pending).signoutRedirect();
  sessionStorage.removeItem(DEV_KEY);
}
