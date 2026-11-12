import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { Plus, Settings2, LockKeyhole, Users } from "lucide-react";
import { toast } from "sonner";
import { useOrg, useRefreshOrg } from "../../hooks/useOrg";
import { api } from "../../services/api";
import { managesOrg, managesTasks } from "../../lib/permissions";
import type { Project, ProjectMember, Member } from "../../types";
import { Form } from "../../components/Form";
import { ErrorState, Loading, Modal } from "../../components/UI";
import Board from "../tasks/Board";
import TaskList from "../tasks/TaskList";
import TaskDialog from "../tasks/TaskDialog";
import Dashboard from "../dashboard/Dashboard";
import { projectFields, projectPayload } from "./projectForm";
export default function ProjectDetail() {
  const org = useOrg();
  const refresh = useRefreshOrg();
  const { projectId } = useParams();
  const [tab, setTab] = useState("board");
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState(false);
  const base = `/organizations/${org.id}/projects/${projectId}`;
  const query = useQuery({
    queryKey: ["org", org.id, "project", projectId],
    queryFn: () => api<Project>(base),
  });
  const members = useQuery({
    queryKey: ["org", org.id, "project-members", projectId],
    queryFn: () => api<ProjectMember[]>(`${base}/members`),
  });
  const team = useQuery({
    queryKey: ["org", org.id, "members"],
    queryFn: () => api<Member[]>(`/organizations/${org.id}/members`),
  });
  if (query.isPending) return <Loading />;
  if (query.error || !query.data) return <ErrorState error={query.error} />;
  const project = query.data;
  const writable =
    managesTasks(org.role, project.projectRole) &&
    !org.archivedAt &&
    project.status !== "ARCHIVED";
  const manage =
    writable &&
    (managesOrg(org.role) || project.projectRole === "PROJECT_MANAGER");
  return (
    <>
      <Link className="back-link" to={`/org/${org.id}/projects`}>
        ← All projects
      </Link>
      <div className="page-heading">
        <div>
          <span className="eyebrow">
            {project.projectKey} ·{" "}
            {project.visibility === "PRIVATE"
              ? "PRIVATE PROJECT"
              : "WORKSPACE PROJECT"}
          </span>
          <h1>{project.name}</h1>
          <p className="muted">
            {project.description || "Good work starts with a clear next step."}
          </p>
        </div>
        <div className="inline-actions">
          {manage && (
            <button className="button" onClick={() => setEditing(true)}>
              <Settings2 size={16} />
              Edit project
            </button>
          )}
          {writable && (
            <button
              className="button primary"
              onClick={() => setCreating(true)}
            >
              <Plus size={16} />
              New task
            </button>
          )}
        </div>
      </div>
      <div className="project-meta">
        <span className={"badge status-" + project.status}>
          {project.status.replaceAll("_", " ")}
        </span>
        <span>
          <LockKeyhole size={13} />
          {project.visibility.toLowerCase()}
        </span>
        <span>
          <Users size={13} />
          {members.data?.length ?? "…"} members
        </span>
        <span>Due {project.dueDate ?? "anytime"}</span>
      </div>
      <div className="tabs" role="tablist" aria-label="Project views">
        {["board", "list", "overview", "members"].map((value) => (
          <button
            role="tab"
            aria-selected={tab === value}
            className={tab === value ? "active" : ""}
            key={value}
            onClick={() => setTab(value)}
          >
            {value === "board"
              ? "Kanban board"
              : value === "list"
                ? "Task list"
                : value[0].toUpperCase() + value.slice(1)}
          </button>
        ))}
      </div>
      {tab === "board" && <Board project={project} />}
      {tab === "list" && <TaskList projectId={project.id} />}
      {tab === "overview" && <Dashboard projectId={project.id} />}
      {tab === "members" && (
        <section className="panel">
          <h3>Project access</h3>
          <p className="muted">
            Private projects are visible to their members and workspace
            administrators. Contributors can manage tasks. Workspace Viewers
            always have read-only access.
          </p>
          {members.error ? (
            <ErrorState error={members.error} />
          ) : (
            <div className="list-rows">
              {members.data?.map((member) => (
                <div className="list-row" key={member.userId}>
                  <div>
                    <strong>{member.name}</strong>
                    <small>{member.email}</small>
                  </div>
                  <span className="badge">{member.role}</span>
                  {manage && (
                    <button
                      className="button quiet danger"
                      onClick={async () => {
                        if (
                          !window.confirm(
                            `Remove ${member.name} from this project?`,
                          )
                        )
                          return;
                        try {
                          await api(
                            `${base}/members/${member.userId}`,
                            "DELETE",
                          );
                          await refresh();
                        } catch (e) {
                          toast.error((e as Error).message);
                        }
                      }}
                    >
                      Remove
                    </button>
                  )}
                </div>
              ))}
            </div>
          )}
          {manage && team.data && (
            <Form
              fields={[
                {
                  name: "userId",
                  label: "Workspace member",
                  required: true,
                  options: team.data.map((member) => ({
                    value: member.userId,
                    label: `${member.name} (${member.email})`,
                  })),
                },
                {
                  name: "role",
                  label: "Project role",
                  options: ["CONTRIBUTOR", "PROJECT_MANAGER", "VIEWER"],
                },
              ]}
              submit="Add or update member"
              onSubmit={async (values) => {
                await api(`${base}/members`, "POST", values);
                await refresh();
                toast.success("Project access updated");
              }}
            />
          )}
        </section>
      )}
      {creating && (
        <TaskDialog
          projectId={project.id}
          open
          onClose={() => setCreating(false)}
        />
      )}
      <Modal title="Project settings" open={editing} onOpenChange={setEditing}>
        <Form
          fields={projectFields.map((f) =>
            f.name === "projectKey"
              ? { ...f, options: [project.projectKey] }
              : f,
          )}
          initial={{
            name: project.name,
            projectKey: project.projectKey,
            description: project.description,
            status: project.status,
            visibility: project.visibility,
            priority: project.priority,
            startDate: project.startDate ?? "",
            dueDate: project.dueDate ?? "",
          }}
          onSubmit={async (values) => {
            await api(base, "PATCH", projectPayload(values));
            await refresh();
            setEditing(false);
            toast.success("Project updated");
          }}
        />
        <button
          className="button danger mt"
          onClick={async () => {
            if (
              !window.confirm(
                "Archive this project? Tasks and history will be retained.",
              )
            )
              return;
            try {
              await api(`${base}/archive`, "POST");
              await refresh();
              setEditing(false);
            } catch (e) {
              toast.error((e as Error).message);
            }
          }}
        >
          Archive project
        </button>
      </Modal>
    </>
  );
}
