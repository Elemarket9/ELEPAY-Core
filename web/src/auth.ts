import type { UserManager } from "oidc-client-ts";

/**
 * Production: OpenID Connect authorization code flow with PKCE against the
 * configured issuer. Development: tokens from the service's /dev/token
 * endpoint (only present in dev/test profiles).
 */
const issuer = import.meta.env.VITE_OIDC_ISSUER as string | undefined;
const clientId = import.meta.env.VITE_OIDC_CLIENT_ID as string | undefined;
export function validIssuer(value: string | undefined, dev = false): boolean {
  if(!value)return false;
  try{const u=new URL(value);return !u.username&&!u.password&&!u.search&&!u.hash&&(u.protocol==="https:"||(dev&&u.protocol==="http:"&&["localhost","127.0.0.1","[::1]"].includes(u.hostname)));}catch{return false;}
}
export const SESSION_EXPIRED = "elepay:session-expired";
let rejectedToken:string|null=null;
export function notifySessionExpired(token?:string){if(token)rejectedToken=token;if(typeof window!=="undefined")window.dispatchEvent(new Event(SESSION_EXPIRED));}

let managerPromise: Promise<UserManager> | null = null;

/** The OIDC client is loaded only when sign-in is configured and needed, keeping checkout pages small. */
function oidc(): Promise<UserManager> | null {
  if (!issuer || !clientId || !validIssuer(issuer,import.meta.env.DEV)) return null;
  managerPromise ??= import("oidc-client-ts").then(({ UserManager, WebStorageStateStore }) => new UserManager({
    authority: issuer,
    client_id: clientId,
    redirect_uri: window.location.origin + "/app/callback",
    post_logout_redirect_uri: window.location.origin,
    response_type: "code",
    automaticSilentRenew: false,
    monitorSession: false,
    stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
    scope: "openid profile",
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  }));
  return managerPromise;
}

const DEV_KEY = "elepay.devToken";

export const oidcEnabled = Boolean(validIssuer(issuer,import.meta.env.DEV) && clientId?.trim());
// Production bundles must never fall back to development token issuance.
export const devSignInEnabled = import.meta.env.DEV && !issuer && !clientId;

export function safeReturnPath(state: unknown): string {
  if (typeof state !== "string" || !state.startsWith("/") || state.startsWith("//") || /[\\\u0000-\u001f\u007f]/.test(state)) return "/";
  const base = "https://elepay.invalid";
  try {
    const url = new URL(state, base);
    return url.origin === base && !url.pathname.startsWith("//") && url.pathname !== "/app/callback" ? url.pathname + url.search + url.hash : "/";
  } catch { return "/"; }
}

export async function getToken(): Promise<string | null> {
  const pending = oidc();
  if (pending) {
    const user = await (await pending).getUser();
    return user && !user.expired && user.access_token!==rejectedToken ? user.access_token : null;
  }
  const token=devSignInEnabled ? sessionStorage.getItem(DEV_KEY) : null;return token===rejectedToken?null:token;
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
  sessionStorage.setItem(DEV_KEY, (await response.json()).accessToken);rejectedToken=null;
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
  sessionStorage.removeItem(DEV_KEY);
  if(pending){const manager=await pending;const user=await manager.getUser();await manager.removeUser();notifySessionExpired();await manager.signoutRedirect({id_token_hint:user?.id_token});}
  else notifySessionExpired();
}
