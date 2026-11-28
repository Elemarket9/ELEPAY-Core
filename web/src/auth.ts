import type { UserManager } from "oidc-client-ts";

/**
 * Supports three authentication modes:
 * 1. Native ELEPAY Auth: Email/Phone + Password + dual Email & SMS 6-digit verification (/v1/auth/*).
 * 2. Production OIDC SSO: Authorization code flow with PKCE against the configured issuer.
 * 3. Development quick sign-in: /dev/token (dev/test/sandbox profiles only).
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
    scope: "openid profile email phone",
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  }));
  return managerPromise;
}

const DEV_KEY = "elepay.devToken";
const NATIVE_KEY = "elepay.nativeToken";
const USER_KEY = "elepay.authUser";

export const oidcEnabled = Boolean(issuer && clientId);

export interface AuthChallenge {
  challengeId: string;
  purpose: "register" | "login" | "reset_password";
  channels: string[];
  maskedEmail: string | null;
  maskedPhone: string | null;
  expiresInSeconds: number;
  emailVerified: boolean;
  smsVerified: boolean;
}

export interface AuthUser {
  id: string;
  fullName: string;
  email: string;
  phone: string;
  roles: string[];
  emailVerified: boolean;
  phoneVerified: boolean;
}

export interface VerifyResult {
  verified: boolean;
  emailVerified: boolean;
  smsVerified: boolean;
  accessToken: string | null;
  tokenType: string | null;
  expiresInSeconds: number;
  user: AuthUser | null;
}

async function authPost<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  let parsed: Record<string, unknown> | null = null;
  try {
    parsed = text ? (JSON.parse(text) as Record<string, unknown>) : null;
  } catch {
    parsed = null;
  }
  if (!response.ok) {
    const msg = (parsed?.detail as string)
      || (parsed?.message as string)
      || (parsed?.title as string)
      || `Authentication request failed (${response.status})`;
    throw new Error(msg);
  }
  return (parsed ?? {}) as T;
}

export async function registerAccount(input: {
  fullName: string;
  email: string;
  phone: string;
  password: string;
}): Promise<AuthChallenge> {
  return authPost<AuthChallenge>("/v1/auth/register", input);
}

export async function loginWithPassword(identifier: string, password: string): Promise<AuthChallenge> {
  return authPost<AuthChallenge>("/v1/auth/login", { identifier, password });
}

export async function verifyEmailAndSms(
  challengeId: string,
  emailCode: string,
  smsCode: string,
): Promise<VerifyResult> {
  const result = await authPost<VerifyResult>("/v1/auth/verify", {
    challengeId,
    emailCode: emailCode.trim(),
    smsCode: smsCode.trim(),
  });
  if (result.verified && result.accessToken) {
    sessionStorage.setItem(NATIVE_KEY, result.accessToken);
  }
  return result;
}

export async function resendVerificationCodes(challengeId: string): Promise<AuthChallenge> {
  return authPost<AuthChallenge>("/v1/auth/resend", { challengeId });
}

export async function requestPasswordReset(identifier: string): Promise<AuthChallenge> {
  return authPost<AuthChallenge>("/v1/auth/forgot-password", { identifier });
}

export async function resetPassword(input: {
  challengeId: string;
  emailCode: string;
  smsCode: string;
  newPassword: string;
}): Promise<{ reset: boolean }> {
  return authPost<{ reset: boolean }>("/v1/auth/reset-password", input);
}

function isJwtExpired(token: string): boolean {
  try {
    const parts = token.split(".");
    if (parts.length !== 3) return true;
    const base64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
    const payload = JSON.parse(atob(padded)) as { exp?: number };
    return typeof payload.exp === "number" && payload.exp * 1000 <= Date.now();
  } catch {
    return true;
  }
}

export async function getToken(): Promise<string | null> {
  const nativeToken = sessionStorage.getItem(NATIVE_KEY);
  if (nativeToken) {
    if (!isJwtExpired(nativeToken)) return nativeToken;
    sessionStorage.removeItem(NATIVE_KEY);
    sessionStorage.removeItem(USER_KEY);
  }
  const pending = oidc();
  if (pending) {
    const user = await (await pending).getUser();
    return user && !user.expired ? user.access_token : null;
  }
  return sessionStorage.getItem(DEV_KEY);
}

export async function signInOidc(): Promise<void> {
  const pending = oidc();
  if (!pending) throw new Error("OIDC SSO is not configured on this server");
  await (await pending).signinRedirect({ state: window.location.pathname });
}

export async function signIn(devSubject?: string, roles: string[] = []): Promise<void> {
  const pending = oidc();
  if (pending && !devSubject) {
    await (await pending).signinRedirect({ state: window.location.pathname });
    return;
  }
  const response = await fetch("/dev/token", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ subject: devSubject, roles }),
  });
  if (!response.ok) throw new Error("Development sign-in is not available on this server");
  sessionStorage.setItem(DEV_KEY, (await response.json()).accessToken);
}

export async function completeSignIn(): Promise<string> {
  const pending = oidc();
  if (!pending) return "/";
  const user = await (await pending).signinRedirectCallback();
  return typeof user.state === "string" ? user.state : "/";
}

export async function signOut(): Promise<void> {
  sessionStorage.removeItem(NATIVE_KEY);
  sessionStorage.removeItem(USER_KEY);
  sessionStorage.removeItem(DEV_KEY);
  const pending = oidc();
  if (pending) {
    const user = await (await pending).getUser();
    if (user) await (await pending).signoutRedirect();
  }
}
