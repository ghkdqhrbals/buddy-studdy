import { Activity, ArrowRight } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { useAdminSession } from "../admin/AdminSessionContext.jsx";
import { Button } from "../components/Button.jsx";
import { InlineNotice } from "../components/InlineNotice.jsx";

function safeNextPath() {
  const value = new URLSearchParams(window.location.search).get("next") || "/";
  return value.startsWith("/") && !value.startsWith("//") && !value.startsWith("/login.html")
    ? value
    : "/";
}

export function LoginPage() {
  const { authenticated, login } = useAdminSession();
  const nextPath = useMemo(safeNextPath, []);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (authenticated) window.location.replace(nextPath);
  }, [authenticated, nextPath]);

  if (authenticated) return null;

  async function submit(event) {
    event.preventDefault();
    setError("");
    setSubmitting(true);
    try {
      await login(username.trim(), password);
      window.location.replace(nextPath);
    } catch (loginError) {
      setError(loginError.message || "Sign in failed");
      setSubmitting(false);
    }
  }

  return (
    <main className="login-page">
      <section className="login-panel" aria-labelledby="login-title">
        <div className="login-brand">
          <span className="login-brand-mark"><Activity size={25} aria-hidden="true" /></span>
          <div><strong>BuddyStudy</strong><span>Monitoring</span></div>
        </div>
        <div className="login-copy">
          <span>Operations console</span>
          <h1 id="login-title">Administrator sign in</h1>
          <p>Use one administrator account for monitoring, TestZone, and every Manage workspace.</p>
        </div>
        <form className="login-form" onSubmit={submit}>
          <label className="field">
            <span>Username</span>
            <input
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoComplete="username"
              autoFocus
              required
            />
          </label>
          <label className="field">
            <span>Password</span>
            <input
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="current-password"
              required
            />
          </label>
          {error ? <InlineNotice tone="danger">{error}</InlineNotice> : null}
          <Button type="submit" icon={ArrowRight} busy={submitting}>Sign in</Button>
        </form>
        <p className="login-security-note">The session is stored only in this browser tab and expires automatically.</p>
      </section>
    </main>
  );
}
