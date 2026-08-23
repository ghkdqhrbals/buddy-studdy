import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import {
  clearAdminSession,
  loginAdmin as requestLogin,
  readAdminSession,
  validateAdminSession,
} from "./adminApi.js";

const AdminSessionContext = createContext(null);

export function AdminSessionProvider({ children }) {
  const [session, setSession] = useState(readAdminSession);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let active = true;
    validateAdminSession()
      .then((validated) => {
        if (active) setSession(validated);
      })
      .finally(() => {
        if (active) setReady(true);
      });
    const handleExpiry = () => {
      setSession(null);
      setReady(true);
    };
    window.addEventListener("monitoring:admin-session-expired", handleExpiry);
    return () => {
      active = false;
      window.removeEventListener("monitoring:admin-session-expired", handleExpiry);
    };
  }, []);

  const login = useCallback(async (username, password) => {
    const next = await requestLogin(username, password);
    setSession(next);
    setReady(true);
    return next;
  }, []);

  const logout = useCallback(() => {
    clearAdminSession();
    setSession(null);
  }, []);

  const expire = useCallback(() => {
    clearAdminSession();
    setSession(null);
  }, []);

  const value = useMemo(
    () => ({ authenticated: Boolean(session), ready, session, login, logout, expire }),
    [expire, login, logout, ready, session],
  );

  return (
    <AdminSessionContext.Provider value={value}>
      {children}
    </AdminSessionContext.Provider>
  );
}

export function useAdminSession() {
  const value = useContext(AdminSessionContext);
  if (!value) throw new Error("useAdminSession must be used inside AdminSessionProvider");
  return value;
}
