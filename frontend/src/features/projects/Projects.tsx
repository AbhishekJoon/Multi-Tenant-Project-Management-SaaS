import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router-dom";
import {
  Plus,
  Search,
  FolderKanban,
  LockKeyhole,
  ArrowUpRight,
  Calendar,
} from "lucide-react";
import { toast } from "sonner";
import { useOrg, useRefreshOrg } from "../../hooks/useOrg";
import { useDebounced } from "../../hooks/useDebounced";
import { api } from "../../services/api";
import { createsProjects } from "../../lib/permissions";
import type { Project, Page } from "../../types";
import { Form } from "../../components/Form";
import { projectFields, projectPayload } from "./projectForm";
import { Empty, ErrorState, Loading, Modal } from "../../components/UI";
export default function Projects() {
  const org = useOrg();
  const refresh = useRefreshOrg();
  const [params, setParams] = useSearchParams();
  const search = params.get("search") ?? "";
  const setSearch = (value: string) =>
    setParams({ search: value }, { replace: true });
  const [status, setStatus] = useState("");
  const [visibility, setVisibility] = useState("");
  const [sort, setSort] = useState("createdAt");
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const keyword = useDebounced(search);
  const queryString = new URLSearchParams({
    search: keyword,
    page: String(page),
    sort,
    ...(status ? { status } : {}),
    ...(visibility ? { visibility } : {}),
  }).toString();
  const query = useQuery({
    queryKey: ["org", org.id, "projects", queryString],
    queryFn: () =>
      api<Page<Project>>(`/organizations/${org.id}/projects?${queryString}`),
  });
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">WHERE IDEAS BECOME PROGRESS</span>
          <h1>Projects with purpose.</h1>
          <p className="muted">Your team’s work, one clear view.</p>
        </div>
        {createsProjects(org.role) && !org.archivedAt && (
          <button className="button primary" onClick={() => setCreating(true)}>
            <Plus size={16} />
            New project
          </button>
        )}
      </div>
      <div className="filters">
        <label className="filter-search">
          <Search size={16} />
          <input
            aria-label="Search project names"
            value={search}
            placeholder="Find a project…"
            onChange={(e) => {
              setSearch(e.target.value);
              setPage(0);
            }}
          />
        </label>
        <select
          aria-label="Project status"
          value={status}
          onChange={(e) => {
            setStatus(e.target.value);
            setPage(0);
          }}
        >
          <option value="">All statuses</option>
          {["PLANNED", "ACTIVE", "ON_HOLD", "COMPLETED", "ARCHIVED"].map(
            (s) => (
              <option key={s}>{s}</option>
            ),
          )}
        </select>
        <select
          aria-label="Project visibility"
          value={visibility}
          onChange={(e) => {
            setVisibility(e.target.value);
            setPage(0);
          }}
        >
          <option value="">All visibility</option>
          <option>ORGANIZATION</option>
          <option>PRIVATE</option>
        </select>
        <select
          aria-label="Sort projects"
          value={sort}
          onChange={(e) => setSort(e.target.value)}
        >
          <option value="createdAt">Newest first</option>
          <option value="name">Name</option>
          <option value="dueDate">Due date</option>
        </select>
      </div>
      {query.isPending ? (
        <Loading />
      ) : query.error ? (
        <ErrorState error={query.error} />
      ) : !query.data?.items.length ? (
        <Empty title="A blank canvas for your next idea">
          <p>
            Create a project or adjust your filters to find your team’s work.
          </p>
        </Empty>
      ) : (
        <>
          <div className="projects-grid">
            {query.data.items.map((project, i) => (
              <Link
                className="project-card"
                key={project.id}
                to={`/org/${org.id}/projects/${project.id}`}
              >
                <div className="project-card-top">
                  <span className={`project-symbol color-${i % 4}`}>
                    <FolderKanban size={21} />
                  </span>
                  <span className={"badge status-" + project.status}>
                    {project.status.replaceAll("_", " ")}
                  </span>
                </div>
                <h3>
                  {project.name}
                  <ArrowUpRight size={16} />
                </h3>
                <p>
                  {project.description ||
                    "Your next great project starts here."}
                </p>
                <div className="project-card-bottom">
                  <span>
                    {project.visibility === "PRIVATE" ? (
                      <LockKeyhole size={13} />
                    ) : (
                      <FolderKanban size={13} />
                    )}
                    {project.projectKey}
                  </span>
                  <span>
                    <Calendar size={13} />
                    {project.dueDate
                      ? new Date(
                          project.dueDate + "T00:00:00",
                        ).toLocaleDateString()
                      : "No due date"}
                  </span>
                </div>
              </Link>
            ))}
          </div>
          <div className="pagination">
            <span>{query.data.total} projects</span>
            <button
              className="button quiet"
              disabled={page === 0}
              onClick={() => setPage(page - 1)}
            >
              Previous
            </button>
            <button
              className="button quiet"
              disabled={(page + 1) * query.data.size >= query.data.total}
              onClick={() => setPage(page + 1)}
            >
              Next
            </button>
          </div>
        </>
      )}
      <Modal
        title="Create a new project"
        open={creating}
        onOpenChange={setCreating}
      >
        <Form
          fields={projectFields}
          submit="Create project"
          onSubmit={async (values) => {
            await api(
              `/organizations/${org.id}/projects`,
              "POST",
              projectPayload(values),
            );
            await refresh();
            setCreating(false);
            toast.success("Your project is ready");
          }}
        />
      </Modal>
    </>
  );
}
