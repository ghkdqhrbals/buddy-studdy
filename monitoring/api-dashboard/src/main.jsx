import { createRoot } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MonitoringApp } from "./MonitoringApp.jsx";
import { AdminSessionProvider } from "./admin/AdminSessionContext.jsx";
import { AdminAuthBoundary } from "./admin/AdminAuthBoundary.jsx";
import { installAuthenticatedFetch } from "./admin/adminApi.js";
import "./styles/manage.css";

installAuthenticatedFetch();

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
  <QueryClientProvider client={queryClient}>
    <AdminSessionProvider>
      <AdminAuthBoundary>
        <MonitoringApp />
      </AdminAuthBoundary>
    </AdminSessionProvider>
  </QueryClientProvider>,
);
