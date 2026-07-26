import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import {
  clearAdminSession,
  loginAdmin as requestLogin,
  readAdminSession,
} from "./adminApi.js";

const AdminSessionContext = createContext(null);

export function AdminSessionProvider({ children }) {
  const [session, setSession] = useState(readAdminSession);

  useEffect(() => {
    const handleExpiry = () => setSession(null);
    window.addEventListener("monitoring:admin-session-expired", handleExpiry);
    return () => window.removeEventListener("monitoring:admin-session-expired", handleExpiry);
  }, []);

  const login = useCallback(async (username, password) => {
    const next = await requestLogin(username, password);
    setSession(next);
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
    () => ({ authenticated: Boolean(session), session, login, logout, expire }),
    [expire, login, logout, session],
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
