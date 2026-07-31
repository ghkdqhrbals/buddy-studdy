import { useEffect } from "react";
import { useAdminSession } from "./AdminSessionContext.jsx";
import { LoginPage } from "../pages/LoginPage.jsx";

const LOGIN_PATH = "/login.html";

function currentDestination() {
  return `${window.location.pathname}${window.location.search}${window.location.hash}`;
}

export function AdminAuthBoundary({ children }) {
  const { authenticated, ready } = useAdminSession();
  const loginRoute = window.location.pathname === LOGIN_PATH;

  useEffect(() => {
    if (ready && !authenticated && !loginRoute) {
      const next = encodeURIComponent(currentDestination());
      window.location.replace(`${LOGIN_PATH}?next=${next}`);
    }
  }, [authenticated, loginRoute, ready]);

  if (loginRoute) return <LoginPage />;
  if (!ready || !authenticated) {
    return (
      <main className="auth-loading" aria-live="polite">
        <span className="auth-loading-mark" />
        <p>Checking administrator session</p>
      </main>
    );
  }
  return children;
}
