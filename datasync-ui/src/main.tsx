import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { HashRouter, Route, Routes } from "react-router-dom";
import { ApiError } from "./api/client";
import { useLiveUpdates } from "./api/live";
import { keys, useMe } from "./api/queries";
import { AppLayout } from "./components/AppLayout";
import { ErrorAlert } from "./components/ErrorAlert";
import { Toaster } from "./components/Toaster";
import { Spinner } from "./components/ui/layout";
import "./index.css";
import { ForcedPasswordChangePage, LoginPage } from "./pages/AuthPages";
import { ConnectionsPage } from "./pages/ConnectionsPage";
import { DashboardPage } from "./pages/DashboardPage";
import { JobDetailPage } from "./pages/jobs/JobDetailPage";
import { JobsPage } from "./pages/jobs/JobsPage";
import { JobWizardPage } from "./pages/jobs/JobWizardPage";
import { SettingsPage } from "./pages/SettingsPage";

/** An expired session anywhere sends the user back to the sign-in page. */
function onError(error: unknown) {
  if (error instanceof ApiError && error.status === 401) queryClient.setQueryData(keys.me, null);
}

const queryClient = new QueryClient({
  queryCache: new QueryCache({ onError }),
  mutationCache: new MutationCache({ onError }),
  defaultOptions: {
    queries: { retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2 },
  },
});

function SignedIn() {
  useLiveUpdates();
  return (
    <AppLayout>
      <Routes>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/jobs" element={<JobsPage />} />
        <Route path="/jobs/new" element={<JobWizardPage />} />
        <Route path="/jobs/:id" element={<JobDetailPage />} />
        <Route path="/jobs/:id/edit" element={<JobWizardPage />} />
        <Route path="/connections" element={<ConnectionsPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="*" element={<p className="text-sm text-muted-foreground">Page not found.</p>} />
      </Routes>
    </AppLayout>
  );
}

function App() {
  const me = useMe();
  if (me.isPending) return <Spinner />;
  if (me.isError) return <div className="mx-auto max-w-md p-8"><ErrorAlert error={me.error} title="Cannot reach the server" /></div>;
  if (!me.data) return <LoginPage />;
  if (me.data.mustChangePassword) return <ForcedPasswordChangePage />;
  return <SignedIn />;
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <HashRouter>
        <App />
      </HashRouter>
      <Toaster />
    </QueryClientProvider>
  </StrictMode>,
);
