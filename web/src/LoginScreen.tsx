import { useEffect, useState } from "react";
import {
  type AuthChallenge,
  loginWithPassword,
  oidcEnabled,
  registerAccount,
  requestPasswordReset,
  resendVerificationCodes,
  resetPassword,
  signIn,
  signInOidc,
  verifyEmailAndSms,
} from "./auth";
import { BrandMark, IconLock } from "./ui/Icons";

type Mode = "login" | "register" | "verify" | "forgot" | "reset";

export function LoginScreen({
  roles = [],
  onAuthenticated,
}: {
  roles?: string[];
  onAuthenticated: () => void;
}) {
  const [mode, setMode] = useState<Mode>("login");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // Sign-in / Register fields
  const [identifier, setIdentifier] = useState("");
  const [fullName, setFullName] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);

  // Dual Email + SMS Verification state
  const [challenge, setChallenge] = useState<AuthChallenge | null>(null);
  const [emailCode, setEmailCode] = useState("");
  const [smsCode, setSmsCode] = useState("");
  const [emailVerified, setEmailVerified] = useState(false);
  const [smsVerified, setSmsVerified] = useState(false);
  const [secondsLeft, setSecondsLeft] = useState(0);

  // Dev quick sign-in
  const [devSubject, setDevSubject] = useState("");

  useEffect(() => {
    if (secondsLeft <= 0) return;
    const t = setTimeout(() => setSecondsLeft((s) => Math.max(0, s - 1)), 1000);
    return () => clearTimeout(t);
  }, [secondsLeft]);

  function openChallenge(next: AuthChallenge, nextMode: "verify" | "reset") {
    setChallenge(next);
    setEmailCode("");
    setSmsCode("");
    setEmailVerified(Boolean(next.emailVerified));
    setSmsVerified(Boolean(next.smsVerified));
    setSecondsLeft(next.expiresInSeconds || 600);
    setMode(nextMode);
  }

  async function handleLogin(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setNotice(null);
    setBusy(true);
    try {
      const ch = await loginWithPassword(identifier.trim(), password);
      openChallenge(ch, "verify");
      setNotice("Verification codes sent to both your email and phone.");
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  async function handleRegister(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setNotice(null);
    if (password.length < 8) {
      setError("Password must be at least 8 characters long.");
      return;
    }
    if (password !== confirmPassword) {
      setError("Passwords do not match.");
      return;
    }
    setBusy(true);
    try {
      const ch = await registerAccount({
        fullName: fullName.trim(),
        email: email.trim(),
        phone: phone.trim(),
        password,
      });
      openChallenge(ch, "verify");
      setNotice("Account created. Enter both your email and SMS verification codes to continue.");
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  async function handleVerify(e: React.FormEvent) {
    e.preventDefault();
    if (!challenge) return;
    setError(null);
    setNotice(null);
    if (!emailVerified && emailCode.trim().length !== 6) {
      setError("Enter the 6-digit code sent to your email.");
      return;
    }
    if (!smsVerified && smsCode.trim().length !== 6) {
      setError("Enter the 6-digit code sent via SMS.");
      return;
    }
    setBusy(true);
    try {
      const res = await verifyEmailAndSms(challenge.challengeId, emailCode, smsCode);
      setEmailVerified(res.emailVerified);
      setSmsVerified(res.smsVerified);
      if (res.verified && res.accessToken) {
        onAuthenticated();
      } else {
        setNotice("One channel verified — enter the remaining code to finish signing in.");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  async function handleResend() {
    if (!challenge || busy) return;
    setError(null);
    setNotice(null);
    setBusy(true);
    try {
      const next = await resendVerificationCodes(challenge.challengeId);
      setChallenge(next);
      setEmailVerified(false);
      setSmsVerified(false);
      setEmailCode("");
      setSmsCode("");
      setSecondsLeft(next.expiresInSeconds || 600);
      setNotice("Fresh verification codes sent to your email and phone.");
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  async function handleForgot(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setNotice(null);
    setBusy(true);
    try {
      const ch = await requestPasswordReset(identifier.trim());
      setPassword("");
      setConfirmPassword("");
      openChallenge(ch, "reset");
      setNotice("If an account exists, reset codes were sent to both email and SMS.");
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  async function handleReset(e: React.FormEvent) {
    e.preventDefault();
    if (!challenge) return;
    setError(null);
    setNotice(null);
    if (password.length < 8) {
      setError("New password must be at least 8 characters long.");
      return;
    }
    if (password !== confirmPassword) {
      setError("Passwords do not match.");
      return;
    }
    setBusy(true);
    try {
      await resetPassword({
        challengeId: challenge.challengeId,
        emailCode: emailCode.trim(),
        smsCode: smsCode.trim(),
        newPassword: password,
      });
      setMode("login");
      setPassword("");
      setConfirmPassword("");
      setChallenge(null);
      setNotice("Password updated. Sign in with your new password.");
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  const mins = Math.floor(secondsLeft / 60);
  const secs = String(secondsLeft % 60).padStart(2, "0");

  return (
    <div className="auth-shell">
      <div className="auth-card" style={{ maxWidth: 440 }}>
        <div className="auth-brand-row">
          <BrandMark size={30} />
          <div>
            <h1 style={{ fontSize: "1.25rem", margin: 0 }}>
              {mode === "register"
                ? "Create ELEPAY Account"
                : mode === "verify"
                  ? "Verify Email & SMS"
                  : mode === "forgot" || mode === "reset"
                    ? "Reset Password"
                    : "Sign in to ELEPAY"}
            </h1>
            <p className="small muted" style={{ margin: 0 }}>
              {roles.includes("ops") ? "Operations & Risk Command Center" : "Merchant, POS & Wallet Portal"}
            </p>
          </div>
        </div>

        {(mode === "login" || mode === "register") && (
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0.4rem", margin: "0.75rem 0" }}>
            <button
              type="button"
              className={mode === "login" ? "" : "secondary"}
              onClick={() => { setMode("login"); setError(null); setNotice(null); }}
            >
              Sign In
            </button>
            <button
              type="button"
              className={mode === "register" ? "" : "secondary"}
              onClick={() => { setMode("register"); setError(null); setNotice(null); }}
            >
              Create Account
            </button>
          </div>
        )}

        {error && <p className="notice error" role="alert">{error}</p>}
        {notice && <p className="notice ok" role="status">{notice}</p>}

        {mode === "login" && (
          <form onSubmit={(e) => void handleLogin(e)}>
            <label>
              Email or Phone Number
              <input
                type="text"
                autoComplete="username"
                value={identifier}
                onChange={(e) => setIdentifier(e.target.value)}
                placeholder="owner@business.gh or 0241234567"
                required
              />
            </label>
            <label>
              Password
              <div style={{ display: "flex", gap: "0.4rem" }}>
                <input
                  type={showPassword ? "text" : "password"}
                  autoComplete="current-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="Enter your password"
                  style={{ flex: 1 }}
                  required
                />
                <button
                  type="button"
                  className="secondary"
                  onClick={() => setShowPassword((v) => !v)}
                  style={{ whiteSpace: "nowrap" }}
                >
                  {showPassword ? "Hide" : "Show"}
                </button>
              </div>
            </label>
            <div style={{ display: "flex", justifyContent: "flex-end", marginBottom: "0.5rem" }}>
              <button
                type="button"
                className="link small"
                onClick={() => { setMode("forgot"); setError(null); setNotice(null); }}
              >
                Forgot password?
              </button>
            </div>
            <button type="submit" className="large full-width" disabled={busy}>
              <IconLock size={16} />
              {busy ? "Sending verification codes…" : "Continue with Email & SMS Code"}
            </button>
          </form>
        )}

        {mode === "register" && (
          <form onSubmit={(e) => void handleRegister(e)}>
            <label>
              Full Name
              <input
                type="text"
                autoComplete="name"
                value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                placeholder="Ama Mensah"
                required
              />
            </label>
            <label>
              Email Address
              <input
                type="email"
                autoComplete="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="ama@business.gh"
                required
              />
            </label>
            <label>
              Phone Number (SMS)
              <input
                type="tel"
                autoComplete="tel"
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
                placeholder="+233241234567 or 0241234567"
                required
              />
            </label>
            <label>
              Password (min. 8 characters)
              <div style={{ display: "flex", gap: "0.4rem" }}>
                <input
                  type={showPassword ? "text" : "password"}
                  autoComplete="new-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="Create a strong password"
                  minLength={8}
                  style={{ flex: 1 }}
                  required
                />
                <button
                  type="button"
                  className="secondary"
                  onClick={() => setShowPassword((v) => !v)}
                  style={{ whiteSpace: "nowrap" }}
                >
                  {showPassword ? "Hide" : "Show"}
                </button>
              </div>
            </label>
            <label>
              Confirm Password
              <input
                type={showPassword ? "text" : "password"}
                autoComplete="new-password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                placeholder="Re-enter password"
                minLength={8}
                required
              />
            </label>
            <button type="submit" className="large full-width" disabled={busy} style={{ marginTop: "0.5rem" }}>
              <IconLock size={16} />
              {busy ? "Creating account…" : "Create Account & Send Codes"}
            </button>
          </form>
        )}

        {mode === "verify" && challenge && (
          <form onSubmit={(e) => void handleVerify(e)}>
            <p className="small muted" style={{ marginTop: 0 }}>
              For your security, ELEPAY requires both your <strong>email</strong> and <strong>SMS</strong> verification codes.
              {secondsLeft > 0 ? ` Expires in ${mins}:${secs}.` : " Codes expired — request new ones."}
            </p>
            <label>
              <span style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                <span>Email Code ({challenge.maskedEmail ?? "email"})</span>
                {emailVerified && <span className="tag active">Verified</span>}
              </span>
              <input
                className="mono"
                inputMode="numeric"
                maxLength={6}
                pattern="\d{6}"
                value={emailCode}
                disabled={emailVerified || busy}
                onChange={(e) => setEmailCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
                placeholder="6-digit email code"
                required={!emailVerified}
              />
            </label>
            <label>
              <span style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                <span>SMS Code ({challenge.maskedPhone ?? "phone"})</span>
                {smsVerified && <span className="tag active">Verified</span>}
              </span>
              <input
                className="mono"
                inputMode="numeric"
                maxLength={6}
                pattern="\d{6}"
                value={smsCode}
                disabled={smsVerified || busy}
                onChange={(e) => setSmsCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
                placeholder="6-digit SMS code"
                required={!smsVerified}
              />
            </label>
            <button type="submit" className="large full-width" disabled={busy} style={{ marginTop: "0.5rem" }}>
              {busy ? "Verifying…" : "Verify & Sign In"}
            </button>
            <div style={{ display: "flex", justifyContent: "space-between", marginTop: "0.75rem" }}>
              <button type="button" className="link small" disabled={busy} onClick={() => void handleResend()}>
                Resend Email &amp; SMS codes
              </button>
              <button
                type="button"
                className="link small"
                disabled={busy}
                onClick={() => { setMode("login"); setChallenge(null); setError(null); setNotice(null); }}
              >
                Back to sign in
              </button>
            </div>
          </form>
        )}

        {mode === "forgot" && (
          <form onSubmit={(e) => void handleForgot(e)}>
            <p className="small muted" style={{ marginTop: 0 }}>
              Enter your registered email or phone number. We will send verification codes to both your email and SMS.
            </p>
            <label>
              Email or Phone Number
              <input
                type="text"
                value={identifier}
                onChange={(e) => setIdentifier(e.target.value)}
                placeholder="owner@business.gh or 0241234567"
                required
              />
            </label>
            <button type="submit" className="large full-width" disabled={busy} style={{ marginTop: "0.5rem" }}>
              {busy ? "Sending codes…" : "Send Reset Codes"}
            </button>
            <div style={{ marginTop: "0.75rem", textAlign: "center" }}>
              <button
                type="button"
                className="link small"
                onClick={() => { setMode("login"); setError(null); setNotice(null); }}
              >
                Back to sign in
              </button>
            </div>
          </form>
        )}

        {mode === "reset" && challenge && (
          <form onSubmit={(e) => void handleReset(e)}>
            <label>
              Email Reset Code ({challenge.maskedEmail ?? "email"})
              <input
                className="mono"
                inputMode="numeric"
                maxLength={6}
                value={emailCode}
                onChange={(e) => setEmailCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
                placeholder="6-digit email code"
                required
              />
            </label>
            <label>
              SMS Reset Code ({challenge.maskedPhone ?? "phone"})
              <input
                className="mono"
                inputMode="numeric"
                maxLength={6}
                value={smsCode}
                onChange={(e) => setSmsCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
                placeholder="6-digit SMS code"
                required
              />
            </label>
            <label>
              New Password
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                minLength={8}
                placeholder="At least 8 characters"
                required
              />
            </label>
            <label>
              Confirm New Password
              <input
                type="password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                minLength={8}
                placeholder="Re-enter new password"
                required
              />
            </label>
            <button type="submit" className="large full-width" disabled={busy} style={{ marginTop: "0.5rem" }}>
              {busy ? "Updating password…" : "Update Password"}
            </button>
            <div style={{ display: "flex", justifyContent: "space-between", marginTop: "0.75rem" }}>
              <button type="button" className="link small" disabled={busy} onClick={() => void handleResend()}>
                Resend Email &amp; SMS codes
              </button>
              <button
                type="button"
                className="link small"
                disabled={busy}
                onClick={() => { setMode("login"); setChallenge(null); setError(null); setNotice(null); }}
              >
                Back to sign in
              </button>
            </div>
          </form>
        )}

        {oidcEnabled && (mode === "login" || mode === "register") && (
          <div style={{ marginTop: "1rem", paddingTop: "0.85rem", borderTop: "1px solid var(--line)" }}>
            <button
              type="button"
              className="secondary full-width"
              disabled={busy}
              onClick={() => {
                setError(null);
                setBusy(true);
                void signInOidc()
                  .catch((e) => setError(e instanceof Error ? e.message : String(e)))
                  .finally(() => setBusy(false));
              }}
            >
              <IconLock size={15} />
              Sign in with ELEPAY SSO
            </button>
          </div>
        )}

        {!oidcEnabled && (mode === "login" || mode === "register") && (
          <details style={{ marginTop: "1rem", paddingTop: "0.75rem", borderTop: "1px solid var(--line)" }}>
            <summary className="small muted" style={{ cursor: "pointer" }}>
              Developer / Sandbox quick sign-in
            </summary>
            <form
              style={{ marginTop: "0.6rem" }}
              onSubmit={async (e) => {
                e.preventDefault();
                setError(null);
                setBusy(true);
                try {
                  await signIn(devSubject, roles);
                  onAuthenticated();
                } catch (err) {
                  setError(err instanceof Error ? err.message : String(err));
                } finally {
                  setBusy(false);
                }
              }}
            >
              <label>
                Subject
                <input
                  value={devSubject}
                  onChange={(e) => setDevSubject(e.target.value)}
                  placeholder={roles.includes("ops") ? "ops-admin@elepay.gh" : "merchant-owner@elepay.gh"}
                  required
                />
              </label>
              <button type="submit" className="secondary full-width" disabled={busy}>
                {busy ? "Signing in…" : "Dev Token Sign-in"}
              </button>
            </form>
          </details>
        )}
      </div>
    </div>
  );
}
