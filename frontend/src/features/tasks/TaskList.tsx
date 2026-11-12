import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Search } from "lucide-react";
import { useOrg } from "../../hooks/useOrg";
import { useDebounced } from "../../hooks/useDebounced";
import { api, useSession } from "../../services/api";
import type { Page, Task } from "../../types";
import { Empty, ErrorState, Loading } from "../../components/UI";
import TaskDialog from "./TaskDialog";
import { statuses } from "./taskConstants";
export default function TaskList({ projectId }: { projectId?: string }) {
  const org = useOrg();
  const { user } = useSession();
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("");
  const [priority, setPriority] = useState("");
  const [overdue, setOverdue] = useState(false);
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState("dueDate");
  const [selected, setSelected] = useState<Task | null>(null);
  const keyword = useDebounced(search);
  const params = new URLSearchParams({
    search: keyword,
    sort,
    page: String(page),
    overdue: String(overdue),
    ...(status ? { status } : {}),
    ...(priority ? { priority } : {}),
    ...(!projectId ? { assignee: user?.id ?? "" } : {}),
  }).toString();
  const query = useQuery({
    queryKey: ["org", org.id, "tasks", projectId, params],
    queryFn: () =>
      api<Page<Task>>(
        `/organizations/${org.id}${projectId ? "/projects/" + projectId : ""}/tasks?${params}`,
      ),
  });
  return (
    <>
      {!projectId && (
        <div className="page-heading">
          <div>
            <span className="eyebrow">A LITTLE FOCUS GOES A LONG WAY</span>
            <h1>Your next moves.</h1>
            <p className="muted">
              Tasks assigned to you, across projects you can access.
            </p>
          </div>
        </div>
      )}
      <div className="filters">
        <label className="filter-search">
          <Search size={16} />
          <input
            aria-label="Search tasks"
            placeholder="Find a task…"
            value={search}
            onChange={(e) => {
              setSearch(e.target.value);
              setPage(0);
            }}
          />
        </label>
        <select
          aria-label="Task status filter"
          value={status}
          onChange={(e) => {
            setStatus(e.target.value);
            setPage(0);
          }}
        >
          <option value="">All statuses</option>
          {statuses.map((s) => (
            <option key={s}>{s}</option>
          ))}
        </select>
        <select
          aria-label="Task priority filter"
          value={priority}
          onChange={(e) => {
            setPriority(e.target.value);
            setPage(0);
          }}
        >
          <option value="">All priorities</option>
          {["LOW", "MEDIUM", "HIGH", "URGENT"].map((s) => (
            <option key={s}>{s}</option>
          ))}
        </select>
        <select
          aria-label="Sort tasks"
          value={sort}
          onChange={(e) => setSort(e.target.value)}
        >
          <option value="dueDate">Due date</option>
          <option value="updatedAt">Recently updated</option>
          <option value="title">Title</option>
        </select>
        <label className="checkbox">
          <input
            type="checkbox"
            checked={overdue}
            onChange={(e) => {
              setOverdue(e.target.checked);
              setPage(0);
            }}
          />
          Overdue only
        </label>
      </div>
      {query.isPending ? (
        <Loading />
      ) : query.error ? (
        <ErrorState error={query.error} />
      ) : !query.data?.items.length ? (
        <Empty title="A clear list. A fresh perspective.">
          <p>
            No tasks match this view. Adjust the filters or create a task in a
            project.
          </p>
        </Empty>
      ) : (
        <section className="panel">
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>TASK</th>
                  <th>STATUS</th>
                  <th>PRIORITY</th>
                  <th>ASSIGNEE</th>
                  <th>DUE DATE</th>
                </tr>
              </thead>
              <tbody>
                {query.data.items.map((task) => (
                  <tr key={task.id}>
                    <td>
                      <button
                        className="table-task"
                        onClick={() => setSelected(task)}
                      >
                        <small>{task.identifier}</small>
                        <strong>{task.title}</strong>
                      </button>
                    </td>
                    <td>
                      <span className={"badge status-" + task.status}>
                        {task.status.replaceAll("_", " ")}
                      </span>
                    </td>
                    <td>
                      <span className={"badge priority-" + task.priority}>
                        {task.priority}
                      </span>
                    </td>
                    <td>{task.assigneeName ?? "Unassigned"}</td>
                    <td>{task.dueDate ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="pagination">
            <span>{query.data.total} tasks</span>
            <button
              className="button"
              disabled={!page}
              onClick={() => setPage(page - 1)}
            >
              Previous
            </button>
            <button
              className="button"
              disabled={(page + 1) * query.data.size >= query.data.total}
              onClick={() => setPage(page + 1)}
            >
              Next
            </button>
          </div>
        </section>
      )}
      {selected && (
        <TaskDialog
          projectId={selected.projectId}
          taskId={selected.id}
          open
          onClose={() => setSelected(null)}
        />
      )}
    </>
  );
}
