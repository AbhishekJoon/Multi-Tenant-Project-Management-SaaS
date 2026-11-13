import { useState } from "react";
import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  closestCorners,
  useDroppable,
  useSensor,
  useSensors,
  type DragEndEvent,
} from "@dnd-kit/core";
import {
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import {
  useInfiniteQuery,
  useMutation,
  useQueryClient,
  type InfiniteData,
} from "@tanstack/react-query";
import { Calendar, GripVertical, Plus, Flag } from "lucide-react";
import { toast } from "sonner";
import { useOrg, useRefreshOrg } from "../../hooks/useOrg";
import { api } from "../../services/api";
import type { Page, Project, Task, TaskStatus } from "../../types";
import { managesTasks } from "../../lib/permissions";
import { Empty, ErrorState, Loading } from "../../components/UI";
import TaskDialog from "./TaskDialog";
import { statuses } from "./taskConstants";
function Card({
  task,
  writable,
  onOpen,
}: {
  task: Task;
  writable: boolean;
  onOpen: () => void;
}) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({
    id: task.id,
    disabled: !writable,
    data: { status: task.status },
  });
  return (
    <article
      ref={setNodeRef}
      style={{
        transform: CSS.Transform.toString(transform),
        transition,
        opacity: isDragging ? 0.45 : 1,
      }}
      className="task-card"
    >
      <div className="task-card-head">
        <span>{task.identifier}</span>
        {writable && (
          <button
            className="drag-handle"
            aria-label={`Move ${task.identifier}`}
            style={{ touchAction: "none" }}
            {...attributes}
            {...listeners}
          >
            <GripVertical size={16} />
          </button>
        )}
      </div>
      <button className="task-card-title" onClick={onOpen}>
        {task.title}
      </button>
      <span className={"badge priority-" + task.priority}>
        <Flag size={10} />
        {task.priority}
      </span>
      <div className="task-card-footer">
        <span>
          <Calendar size={12} />
          {task.dueDate
            ? new Date(task.dueDate + "T00:00:00").toLocaleDateString(
                undefined,
                { month: "short", day: "numeric" },
              )
            : "No due date"}
        </span>
        <span className="avatar tiny" title={task.assigneeName ?? "Unassigned"}>
          {task.assigneeName?.slice(0, 2).toUpperCase() ?? "—"}
        </span>
      </div>
    </article>
  );
}
function Column({
  status,
  tasks,
  writable,
  onOpen,
  onCreate,
}: {
  status: TaskStatus;
  tasks: Task[];
  writable: boolean;
  onOpen: (task: Task) => void;
  onCreate: () => void;
}) {
  const { setNodeRef, isOver } = useDroppable({ id: status });
  return (
    <section
      className={"board-column" + (isOver ? " drag-over" : "")}
      ref={setNodeRef}
      aria-label={status.replaceAll("_", " ")}
    >
      <header>
        <span className={"status-dot status-" + status} />
        <h3>{status.replaceAll("_", " ")}</h3>
        <span>{tasks.length}</span>
        {writable && (
          <button
            className="icon-button"
            aria-label={`Add task to ${status}`}
            onClick={onCreate}
          >
            <Plus size={14} />
          </button>
        )}
      </header>
      <SortableContext
        items={tasks.map((t) => t.id)}
        strategy={verticalListSortingStrategy}
      >
        {tasks.map((task) => (
          <Card
            key={task.id}
            task={task}
            writable={writable}
            onOpen={() => onOpen(task)}
          />
        ))}
      </SortableContext>
      {!tasks.length && (
        <div className="empty-column">A little space for what’s next.</div>
      )}
    </section>
  );
}
export default function Board({ project }: { project: Project }) {
  const org = useOrg();
  const refresh = useRefreshOrg();
  const cache = useQueryClient();
  const [selected, setSelected] = useState<string | undefined>();
  const [dialog, setDialog] = useState(false);
  const [initialStatus, setInitialStatus] = useState<TaskStatus>("TODO");
  const writable =
    managesTasks(org.role, project.projectRole) &&
    !org.archivedAt &&
    project.status !== "ARCHIVED";
  const key = ["org", org.id, "board", project.id];
  const base = `/organizations/${org.id}/projects/${project.id}/tasks`;
  const query = useInfiniteQuery({
    queryKey: key,
    initialPageParam: 0,
    queryFn: ({ pageParam }) =>
      api<Page<Task>>(`${base}?page=${pageParam}&size=100`),
    getNextPageParam: (last) =>
      (last.page + 1) * last.size < last.total ? last.page + 1 : undefined,
  });
  const tasks = query.data?.pages.flatMap((page) => page.items) ?? [];
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 7 } }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    }),
  );
  const movement = useMutation({
    mutationFn: ({
      task,
      status,
      beforeId,
    }: {
      task: Task;
      status: TaskStatus;
      beforeId: string | null;
    }) =>
      api(`${base}/${task.id}/move`, "PATCH", {
        status,
        beforeId,
        version: task.version,
      }),
    onMutate: async ({ task, status, beforeId }) => {
      await cache.cancelQueries({ queryKey: key });
      const previous = cache.getQueryData<InfiniteData<Page<Task>>>(key);
      const position = beforeId
        ? (tasks.find((t) => t.id === beforeId)?.position ?? 0) - 0.5
        : Math.max(
            -1,
            ...tasks.filter((t) => t.status === status).map((t) => t.position),
          ) + 1;
      cache.setQueryData<InfiniteData<Page<Task>>>(key, (old) =>
        old
          ? {
              ...old,
              pages: old.pages.map((page) => ({
                ...page,
                items: page.items.map((t) =>
                  t.id === task.id ? { ...t, status, position } : t,
                ),
              })),
            }
          : old,
      );
      return { previous };
    },
    onError: (error, _, context) => {
      if (context?.previous) cache.setQueryData(key, context.previous);
      toast.error(error.message);
    },
    onSettled: async () => {
      await refresh();
    },
  });
  function dragEnd(event: DragEndEvent) {
    if (!event.over || event.active.id === event.over.id || movement.isPending)
      return;
    const task = tasks.find((t) => t.id === event.active.id);
    const target = tasks.find((t) => t.id === event.over?.id);
    const status =
      target?.status ??
      (statuses.includes(event.over.id as TaskStatus)
        ? (event.over.id as TaskStatus)
        : undefined);
    if (task && status)
      movement.mutate({ task, status, beforeId: target?.id ?? null });
  }
  return (
    <>
      {query.isPending ? (
        <Loading />
      ) : query.error ? (
        <ErrorState error={query.error} />
      ) : (
        <>
          <div className="board-note">
            <span>
              {tasks.length} of {query.data?.pages[0]?.total ?? 0} tasks · Drag
              a handle or open a task to change its status.
            </span>
            {movement.isPending && <span role="status">Saving board…</span>}
          </div>
          <DndContext
            sensors={sensors}
            collisionDetection={closestCorners}
            onDragEnd={dragEnd}
          >
            <div className="kanban-board">
              {statuses.map((status) => (
                <Column
                  key={status}
                  status={status}
                  tasks={tasks
                    .filter((t) => t.status === status)
                    .sort(
                      (a, b) =>
                        a.position - b.position || a.id.localeCompare(b.id),
                    )}
                  writable={writable && !movement.isPending}
                  onOpen={(task) => {
                    setSelected(task.id);
                    setDialog(true);
                  }}
                  onCreate={() => {
                    setSelected(undefined);
                    setInitialStatus(status);
                    setDialog(true);
                  }}
                />
              ))}
            </div>
          </DndContext>
          {!tasks.length && !writable && <Empty title="No tasks yet" />}
          {query.hasNextPage && (
            <button
              className="button mt"
              onClick={() => void query.fetchNextPage()}
              disabled={query.isFetchingNextPage}
            >
              Load more tasks
            </button>
          )}
        </>
      )}
      {dialog && (
        <TaskDialog
          projectId={project.id}
          taskId={selected}
          initialStatus={initialStatus}
          open
          onClose={() => setDialog(false)}
        />
      )}
    </>
  );
}
