import { Suspense, lazy } from "react";
import { BrowserRouter, Navigate, Route, Routes, Link } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Toaster } from "sonner";
import { bootstrap, useSession } from "../services/api";
import { Loading } from "../components/UI";
const AuthPage = lazy(() => import("../features/auth/AuthPage"));
const AuthCallback = lazy(() => import("../features/auth/AuthCallback"));
const Landing = lazy(() => import("../features/auth/Landing"));
const Workspaces = lazy(() => import("../features/organizations/Workspaces"));
const WorkspaceLayout = lazy(() => import("../layouts/WorkspaceLayout"));
const Dashboard = lazy(() => import("../features/dashboard/Dashboard"));
const Projects = lazy(() => import("../features/projects/Projects"));
const ProjectDetail = lazy(() => import("../features/projects/ProjectDetail"));
const TaskList = lazy(() => import("../features/tasks/TaskList"));
const Team = lazy(() => import("../features/organizations/Team"));
const Settings = lazy(() => import("../features/organizations/Settings"));
const Profile = lazy(() => import("../features/auth/Profile"));
const Updates = lazy(() => import("../features/dashboard/Updates"));
const cache = new QueryClient({
  defaultOptions: { queries: { staleTime: 20000, retry: 1 } },
});
function AppRoutes() {
  const { ready, user } = useSession();
  if (!ready) return <Loading />;
  return (
    <Suspense fallback={<Loading />}>
      <Routes>
        <Route path="/auth/callback" element={<AuthCallback />} />
        <Route
          path="/"
          element={user ? <Navigate to="/workspaces" replace /> : <Landing />}
        />
        {[
          "login",
          "register",
          "forgot-password",
          "reset-password",
          "verify-email",
          "accept-invitation",
          "resend-confirmation",
        ].map((mode) => (
          <Route
            key={mode}
            path={"/" + mode}
            element={<AuthPage key={mode} mode={mode} />}
          />
        ))}
        <Route
          path="/workspaces"
          element={user ? <Workspaces /> : <Navigate to="/login" replace />}
        />
        <Route
          path="/org/:orgId"
          element={
            user ? <WorkspaceLayout /> : <Navigate to="/login" replace />
          }
        >
          <Route index element={<Navigate to="dashboard" replace />} />
          <Route path="dashboard" element={<Dashboard />} />
          <Route path="analytics" element={<Dashboard analytics />} />
          <Route path="projects" element={<Projects />} />
          <Route path="projects/:projectId" element={<ProjectDetail />} />
          <Route path="tasks" element={<TaskList />} />
          <Route path="team" element={<Team />} />
          <Route path="settings" element={<Settings />} />
          <Route path="profile" element={<Profile />} />
          <Route
            path="notifications"
            element={<Updates mode="notifications" />}
          />
          <Route path="activity" element={<Updates mode="activity" />} />
          <Route path="audit" element={<Updates mode="audit" />} />
        </Route>
        <Route
          path="*"
          element={
            <main className="workspace-page">
              <h1>Page not found</h1>
              <Link to="/workspaces">Back to your workspaces</Link>
            </main>
          }
        />
      </Routes>
    </Suspense>
  );
}
void bootstrap();
export default function App() {
  return (
    <QueryClientProvider client={cache}>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
      <Toaster richColors position="bottom-right" />
    </QueryClientProvider>
  );
}
