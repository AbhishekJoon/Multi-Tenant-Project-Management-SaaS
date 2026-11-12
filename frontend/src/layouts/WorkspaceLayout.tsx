import { useState, useEffect } from "react";
import {
  Link,
  NavLink,
  Outlet,
  useNavigate,
  useParams,
} from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Layers3,
  LayoutDashboard,
  FolderKanban,
  CircleCheck,
  Users,
  ChartNoAxesCombined,
  Settings,
  Bell,
  Search,
  ChevronsUpDown,
  LogOut,
  Sun,
  Moon,
  Menu,
  X,
  Activity,
} from "lucide-react";
import { toast } from "sonner";
import { api, setSession, useSession } from "../services/api";
import type { Organization, Notification, Page } from "../types";
import { ErrorState, Loading } from "../components/UI";
export default function WorkspaceLayout() {
  const { orgId } = useParams();
  const navigate = useNavigate();
  const cache = useQueryClient();
  const { user } = useSession();
  const [mobile, setMobile] = useState(false);
  const [search, setSearch] = useState("");
  const [dark, setDark] = useState(
    () => localStorage.getItem("teamflow-theme") === "dark",
  );
  useEffect(() => {
    document.documentElement.dataset.theme = dark ? "dark" : "light";
  }, [dark]);
  const org = useQuery({
    queryKey: ["org", orgId],
    queryFn: () => api<Organization>(`/organizations/${orgId}`),
  });
  const all = useQuery({
    queryKey: ["organizations"],
    queryFn: () => api<Organization[]>("/organizations"),
  });
  const notices = useQuery({
    queryKey: ["org", orgId, "notifications", 0],
    queryFn: () =>
      api<Page<Notification>>(`/organizations/${orgId}/notifications`),
    refetchInterval: 30000,
  });
  const links = [
    { path: "dashboard", label: "Overview", icon: LayoutDashboard },
    { path: "tasks", label: "My tasks", icon: CircleCheck },
    { path: "projects", label: "Projects", icon: FolderKanban },
    { path: "team", label: "Team members", icon: Users },
    { path: "analytics", label: "Analytics", icon: ChartNoAxesCombined },
    { path: "activity", label: "Activity", icon: Activity },
  ];
  if (org.isPending) return <Loading />;
  if (org.error || !org.data)
    return (
      <main className="workspace-page">
        <ErrorState error={org.error} />
        <Link className="text-link" to="/workspaces">
          Back to workspaces
        </Link>
      </main>
    );
  const current = org.data;
  const unread = notices.data?.items.filter((n) => !n.readAt).length ?? 0;
  return (
    <div className="app-shell" data-theme={dark ? "dark" : "light"}>
      <a className="skip-link" href="#main-content">
        Skip to content
      </a>
      {mobile && (
        <button
          className="nav-backdrop"
          aria-label="Dismiss navigation"
          onClick={() => setMobile(false)}
        />
      )}
      <aside className={"sidebar" + (mobile ? " open" : "")}>
        <div className="sidebar-brand">
          <Link className="brand" to="/workspaces">
            <Layers3 size={27} />
            teamflow<span className="brand-dot">.</span>
          </Link>
          <button
            className="icon-button mobile-only"
            aria-label="Close navigation"
            onClick={() => setMobile(false)}
          >
            <X size={20} />
          </button>
        </div>
        <div className="org-switch">
          <span className="org-initial">
            {current.name.slice(0, 1).toUpperCase()}
          </span>
          <div>
            <span className="tiny-label">WORKSPACE</span>
            <select
              aria-label="Active organization"
              value={current.id}
              onChange={(e) => {
                setMobile(false);
                navigate(`/org/${e.target.value}/dashboard`);
              }}
            >
              {(all.data ?? [current]).map((o) => (
                <option key={o.id} value={o.id}>
                  {o.name}
                </option>
              ))}
            </select>
          </div>
          <ChevronsUpDown size={14} />
        </div>
        <span className="nav-section">WORKSPACE</span>
        <nav aria-label="Main navigation">
          {links.map((item) => (
            <NavLink
              key={item.path}
              to={`/org/${orgId}/${item.path}`}
              onClick={() => setMobile(false)}
            >
              <item.icon size={18} />
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <NavLink
            to={`/org/${orgId}/settings`}
            onClick={() => setMobile(false)}
          >
            <Settings size={18} />
            Settings
          </NavLink>
          <Link to="/workspaces" className="workspace-link">
            Switch or create workspace ↗
          </Link>
          <div className="profile-row">
            <Link to={`/org/${orgId}/profile`} className="profile-link">
              <span className="avatar">
                {user?.name.slice(0, 2).toUpperCase()}
              </span>
              <div>
                <strong>{user?.name}</strong>
                <small>{current.role.toLowerCase()}</small>
              </div>
            </Link>
            <button
              aria-label="Sign out"
              className="icon-button"
              onClick={async () => {
                try {
                  await api("/auth/logout", "POST");
                  setSession(null);
                  cache.clear();
                  navigate("/login");
                } catch (e) {
                  toast.error((e as Error).message);
                }
              }}
            >
              <LogOut size={17} />
            </button>
          </div>
        </div>
      </aside>
      <div className="main-shell">
        <header className="topbar">
          <button
            className="icon-button mobile-only"
            aria-label="Open navigation"
            onClick={() => setMobile(true)}
          >
            <Menu size={21} />
          </button>
          <span className="breadcrumb">
            Workspace <span>/</span> <strong>{current.name}</strong>
          </span>
          <form
            className="global-search"
            onSubmit={(e) => {
              e.preventDefault();
              navigate(
                `/org/${orgId}/projects?search=${encodeURIComponent(search)}`,
              );
            }}
          >
            <Search size={16} />
            <input
              aria-label="Search projects"
              placeholder="Search projects…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </form>
          <button
            className="icon-button"
            aria-label="Toggle theme"
            onClick={() => {
              localStorage.setItem("teamflow-theme", dark ? "light" : "dark");
              setDark(!dark);
            }}
          >
            {dark ? <Sun size={18} /> : <Moon size={18} />}
          </button>
          <Link
            className="notification-button icon-button"
            aria-label={`Notifications${unread ? ", unread messages" : ""}`}
            to={`/org/${orgId}/notifications`}
          >
            <Bell size={19} />
            {unread > 0 && <span className="notification-dot" />}
          </Link>
          <Link to={`/org/${orgId}/profile`} className="avatar small">
            {user?.name.slice(0, 2).toUpperCase()}
          </Link>
        </header>
        <main id="main-content" className="main-content">
          {current.archivedAt && (
            <div className="error-banner">
              This workspace is archived. Its history is retained.
            </div>
          )}
          <Outlet context={current} />
        </main>
        <footer className="app-footer">
          A little clarity, every day.<span>TeamFlow Workspace</span>
        </footer>
      </div>
    </div>
  );
}
