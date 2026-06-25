import { FormEvent, useState } from "react";
import { login, storeToken } from "./api";
import { Icon } from "./AdminShell";
import type { Theme } from "./types";

type LoginScreenProps = {
  onLoggedIn: (token: string) => void;
  theme: Theme;
  setTheme: (theme: Theme) => void;
  error: string | null;
};

export function LoginScreen({ onLoggedIn, theme, setTheme, error }: LoginScreenProps) {
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("admin");
  const [busy, setBusy] = useState(false);
  const [loginError, setLoginError] = useState<string | null>(error);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setLoginError(null);
    try {
      const response = await login(username, password);
      storeToken(response);
      onLoggedIn(response.adminToken);
    } catch (err) {
      setLoginError(err instanceof Error ? err.message : "Login failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-shell">
      <form className="login-card" onSubmit={handleSubmit}>
        <div className="login-head">
          <div className="login-brand">
            <div className="login-mark">B</div>
            <div>
              <strong>BuddyStuddy</strong>
              <span>Admin</span>
            </div>
          </div>
          <button type="button" className="secondary-button square-button" aria-label="Toggle theme" onClick={() => setTheme(theme === "light" ? "dark" : "light")}>
            <Icon name={theme === "light" ? "moon" : "sun"} />
          </button>
        </div>
        <div className="login-copy">
          <h1>Sign in</h1>
          <p>Monitor metrics, jobs, and operational health.</p>
        </div>
        <label>
          <span>ID</span>
          <input value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" required />
        </label>
        <label>
          <span>Password</span>
          <input value={password} onChange={(event) => setPassword(event.target.value)} type="password" autoComplete="current-password" required />
        </label>
        {loginError ? <div className="form-error">{loginError}</div> : null}
        <button className="primary-button full" disabled={busy}>
          {busy ? "Signing in" : "Sign in"}
        </button>
      </form>
    </div>
  );
}
