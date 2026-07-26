import { LockKeyhole, LogIn } from "lucide-react";
import { useState } from "react";
import { useAdminSession } from "./AdminSessionContext.jsx";
import { Button } from "../components/Button.jsx";
import { InlineNotice } from "../components/InlineNotice.jsx";

export function AdminGate({ children }) {
  const { authenticated, login } = useAdminSession();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  if (authenticated) return children;

  async function handleSubmit(event) {
    event.preventDefault();
    setError("");
    setSubmitting(true);
    try {
      await login(username.trim(), password);
      setPassword("");
    } catch (loginError) {
      setError(loginError.message || "Sign in failed");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="admin-gate" aria-labelledby="admin-gate-title">
      <div className="admin-gate-mark" aria-hidden="true">
        <LockKeyhole size={22} />
      </div>
      <div className="admin-gate-copy">
        <h2 id="admin-gate-title">Administrator access</h2>
        <p>Sign in with the backend administrator account. The token remains in this browser session only.</p>
      </div>
      <form className="admin-gate-form" onSubmit={handleSubmit}>
        <label className="field">
          <span>Username</span>
          <input
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            autoComplete="username"
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
        <Button type="submit" icon={LogIn} busy={submitting}>
          Sign in
        </Button>
      </form>
      {error ? <InlineNotice tone="danger">{error}</InlineNotice> : null}
    </section>
  );
}
