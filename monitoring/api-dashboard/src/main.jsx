import React from "react";
import { createRoot } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MonitoringApp } from "./MonitoringApp.jsx";
import { AdminSessionProvider } from "./admin/AdminSessionContext.jsx";
import "./styles/manage.css";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchOnWindowFocus: false,
      retry: (failureCount, error) => error?.status !== 401 && failureCount < 1,
      staleTime: 15_000,
    },
    mutations: {
      retry: false,
    },
  },
});

createRoot(document.querySelector("#monitoring-react-root")).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <AdminSessionProvider>
        <MonitoringApp />
      </AdminSessionProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
