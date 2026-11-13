import { statuses } from "./taskConstants";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { toast } from "sonner";
import { useOrg, useRefreshOrg } from "../../hooks/useOrg";
import { api, useSession } from "../../services/api";
import { managesOrg, managesTasks } from "../../lib/permissions";
import type {
  Project,
  Task,
  ProjectMember,
  Comment,
  Page,
  Activity,
  TaskStatus,
} from "../../types";
import { Form, type Field } from "../../components/Form";
import { Empty, ErrorState, Loading, Modal } from "../../components/UI";
import { ActivityList } from "../dashboard/Dashboard";
export default function TaskDialog({
  projectId,
  taskId,
  open,
  onClose,
  initialStatus = "TODO",
}: {
  projectId: string;
  taskId?: string;
  open: boolean;
  onClose: () => void;
  initialStatus?: TaskStatus;
}) {
  const org = useOrg();
  const refresh = useRefreshOrg();
  const { user } = useSession();
  const [commentPage, setCommentPage] = useState(0);
  const base = `/organizations/${org.id}/projects/${projectId}`;
  const project = useQuery({
    queryKey: ["org", org.id, "project", projectId],
    queryFn: () => api<Project>(base),
    enabled: open,
  });
  const task = useQuery({
    queryKey: ["org", org.id, "task", taskId],
    queryFn: () => api<Task>(`${base}/tasks/${taskId}`),
    enabled: open && !!taskId,
  });
  const members = useQuery({
    queryKey: ["org", org.id, "project-members", projectId],
    queryFn: () => api<ProjectMember[]>(`${base}/members`),
    enabled: open,
  });
  const comments = useQuery({
    queryKey: ["org", org.id, "comments", taskId, commentPage],
    queryFn: () =>
      api<Page<Comment>>(
        `${base}/tasks/${taskId}/comments?page=${commentPage}`,
      ),
    enabled: open && !!taskId,
  });
  const activity = useQuery({
    queryKey: ["org", org.id, "task-activity", taskId],
    queryFn: () =>
      api<Page<Activity>>(`${base}/tasks/${taskId}/activity?size=15`),
    enabled: open && !!taskId,
  });
  const writable =
    !!project.data &&
    managesTasks(org.role, project.data.projectRole) &&
    project.data.status !== "ARCHIVED" &&
    !org.archivedAt;
  const fields: Field[] = [
    { name: "title", label: "Task title", required: true, max: 200 },
    { name: "description", label: "Description", type: "textarea", max: 10000 },
    { name: "status", label: "Status", options: [...statuses] },
    {
      name: "priority",
      label: "Priority",
      options: ["MEDIUM", "LOW", "HIGH", "URGENT"],
    },
    {
      name: "assigneeId",
      label: "Assignee",
      options: [
        { value: "", label: "Unassigned" },
        ...(members.data ?? [])
          .filter((m) => m.role !== "VIEWER")
          .map((m) => ({ value: m.userId, label: m.name })),
      ],
    },
    { name: "dueDate", label: "Due date", type: "date" },
    { name: "estimatedEffort", label: "Estimated hours", type: "number" },
  ];
  const current = task.data;
  async function commentAction(id: string, method: string, body?: unknown) {
    try {
      await api(`${base}/tasks/${taskId}/comments/${id}`, method, body);
      await refresh();
    } catch (e) {
      toast.error((e as Error).message);
    }
  }
  return (
    <Modal
      title={current ? current.identifier : "Create a task"}
      open={open}
      onOpenChange={(value) => {
        if (!value) onClose();
      }}
    >
      {project.error || task.error ? (
        <ErrorState error={project.error ?? task.error} />
      ) : project.isPending || (taskId && task.isPending) ? (
        <Loading />
      ) : (
        <>
          {writable ? (
            <Form
              key={current?.version ?? "new"}
              fields={fields}
              initial={
                current
                  ? {
                      title: current.title,
                      description: current.description,
                      status: current.status,
                      priority: current.priority,
                      assigneeId: current.assigneeId ?? "",
                      dueDate: current.dueDate ?? "",
                      estimatedEffort:
                        current.estimatedEffort?.toString() ?? "",
                    }
                  : { status: initialStatus }
              }
              submit={taskId ? "Save task" : "Create task"}
              onSubmit={async (values) => {
                await api(
                  `${base}/tasks${taskId ? "/" + taskId : ""}`,
                  taskId ? "PATCH" : "POST",
                  {
                    ...values,
                    assigneeId: values.assigneeId || null,
                    dueDate: values.dueDate || null,
                    estimatedEffort: values.estimatedEffort
                      ? Number(values.estimatedEffort)
                      : null,
                    ...(taskId ? { version: current?.version } : {}),
                  },
                );
                await refresh();
                toast.success(taskId ? "Task saved" : "Task created");
                if (!taskId) onClose();
              }}
            />
          ) : (
            current && (
              <div className="task-readonly">
                <h2>{current.title}</h2>
                <p>{current.description}</p>
                <span className="badge">{current.status}</span>
                <span className="badge">{current.priority}</span>
                <p className="muted">
                  Assigned to {current.assigneeName ?? "nobody"} · Due{" "}
                  {current.dueDate ?? "anytime"}
                </p>
              </div>
            )
          )}
          {taskId && writable && (
            <div className="task-dialog-actions">
              <button
                className="button quiet"
                onClick={() => void task.refetch()}
              >
                Reload latest version
              </button>
              <button
                className="button quiet danger"
                onClick={async () => {
                  if (
                    !window.confirm(
                      "Delete this task? Its history will be retained.",
                    )
                  )
                    return;
                  try {
                    await api(
                      `${base}/tasks/${taskId}?version=${current?.version}`,
                      "DELETE",
                    );
                    await refresh();
                    onClose();
                  } catch (e) {
                    toast.error((e as Error).message);
                  }
                }}
              >
                Delete task
              </button>
            </div>
          )}
          {taskId && (
            <section className="task-comments">
              <h3>Conversation</h3>
              {comments.error ? (
                <ErrorState error={comments.error} />
              ) : comments.isPending ? (
                <Loading />
              ) : !comments.data?.items.length ? (
                <Empty title="Start a conversation" />
              ) : (
                comments.data.items.map((comment) => (
                  <div className="comment" key={comment.id}>
                    <div>
                      <strong>{comment.authorName}</strong>
                      <time>
                        {new Date(comment.createdAt).toLocaleString()}
                      </time>
                    </div>
                    <p>{comment.body}</p>
                    {writable &&
                      (comment.authorId === user?.id ||
                        managesOrg(org.role)) && (
                        <div>
                          {comment.authorId === user?.id && (
                            <button
                              className="text-link"
                              onClick={() => {
                                const body = window.prompt(
                                  "Edit your comment",
                                  comment.body,
                                );
                                if (body?.trim())
                                  void commentAction(comment.id, "PATCH", {
                                    body,
                                  });
                              }}
                            >
                              Edit
                            </button>
                          )}
                          <button
                            className="text-link danger"
                            onClick={() => {
                              if (window.confirm("Delete this comment?"))
                                void commentAction(comment.id, "DELETE");
                            }}
                          >
                            Delete
                          </button>
                        </div>
                      )}
                  </div>
                ))
              )}
              {comments.data && comments.data.total > comments.data.size && (
                <div className="pagination">
                  <button
                    disabled={commentPage === 0}
                    className="button"
                    onClick={() => setCommentPage(commentPage - 1)}
                  >
                    Previous
                  </button>
                  <button
                    className="button"
                    disabled={
                      (commentPage + 1) * comments.data.size >=
                      comments.data.total
                    }
                    onClick={() => setCommentPage(commentPage + 1)}
                  >
                    Next
                  </button>
                </div>
              )}
              {writable && (
                <Form
                  fields={[
                    {
                      name: "body",
                      label: "Your comment",
                      type: "textarea",
                      required: true,
                      max: 5000,
                    },
                  ]}
                  submit="Post comment"
                  onSubmit={async (values) => {
                    await api(
                      `${base}/tasks/${taskId}/comments`,
                      "POST",
                      values,
                    );
                    await refresh();
                    toast.success("Comment posted");
                  }}
                />
              )}
            </section>
          )}
          {taskId && (
            <section className="task-comments">
              <h3>Task history</h3>
              {activity.error ? (
                <ErrorState error={activity.error} />
              ) : (
                activity.data && <ActivityList items={activity.data.items} />
              )}
            </section>
          )}
        </>
      )}
    </Modal>
  );
}
