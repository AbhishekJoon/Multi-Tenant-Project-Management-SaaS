import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import {
  ArrowUpRight,
  FolderKanban,
  CircleCheck,
  Clock3,
  AlertCircle,
  ArrowRight,
  Check,
  Activity as ActivityIcon,
} from "lucide-react";
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  PieChart,
  Pie,
  Cell,
  AreaChart,
  Area,
} from "recharts";
import { useOrg } from "../../hooks/useOrg";
import { api, useSession } from "../../services/api";
import type { Dashboard as DashboardData, Activity } from "../../types";
import { Empty, ErrorState, Loading } from "../../components/UI";
const colors = ["#28755c", "#80b79e", "#aecdc0", "#e3bd70", "#96a9a2"];
export function ActivityList({ items }: { items: Activity[] }) {
  return items.length ? (
    <div className="activity-list">
      {items.map((item) => (
        <div className="activity-row" key={item.id}>
          <span className="activity-icon">
            <ActivityIcon size={14} />
          </span>
          <div>
            <p>
              <strong>{item.actorName}</strong>{" "}
              {item.action.toLowerCase().replaceAll("_", " ")}
            </p>
            <small>{item.detail}</small>
          </div>
          <time>{new Date(item.createdAt).toLocaleString()}</time>
        </div>
      ))}
    </div>
  ) : (
    <Empty title="A fresh start">
      <p>Your team’s activity will appear here as work gets moving.</p>
    </Empty>
  );
}
export default function Dashboard({
  analytics = false,
  projectId,
}: {
  analytics?: boolean;
  projectId?: string;
}) {
  const org = useOrg();
  const { user } = useSession();
  const query = useQuery({
    queryKey: ["org", org.id, "dashboard", projectId],
    queryFn: () =>
      api<DashboardData>(
        `/organizations/${org.id}${projectId ? "/projects/" + projectId : ""}/dashboard`,
      ),
  });
  if (query.isPending) return <Loading />;
  if (query.error || !query.data) return <ErrorState error={query.error} />;
  const data = query.data;
  const cards = [
    {
      label: "Projects",
      value: data.stats.totalProjects,
      sub: "Across your accessible projects",
      icon: FolderKanban,
      className: "green",
    },
    {
      label: "Tasks completed",
      value: data.stats.completedTasks,
      sub: `${data.stats.totalTasks} tasks in total`,
      icon: CircleCheck,
      className: "blue",
    },
    {
      label: "In progress",
      value: data.stats.inProgressTasks,
      sub: `${data.stats.myTasks} open tasks assigned to you`,
      icon: Clock3,
      className: "amber",
    },
    {
      label: "Overdue tasks",
      value: data.stats.overdueTasks,
      sub: "A little attention goes a long way",
      icon: AlertCircle,
      className: "rose",
    },
  ];
  return (
    <>
      <div className="page-heading">
        <div>
          <span className="eyebrow">
            {analytics ? "THE BIGGER PICTURE" : "YOUR WORKSPACE, AT A GLANCE"}
          </span>
          <h1>
            {analytics
              ? "Progress you can see."
              : `Good to see you, ${user?.name.split(" ")[0]}.`}
          </h1>
          <p className="muted">
            {analytics
              ? "Real progress, thoughtfully measured across projects you can access."
              : "Here’s what’s happening with your team today."}
          </p>
        </div>
        <Link className="button" to={`/org/${org.id}/projects`}>
          View projects <ArrowUpRight size={16} />
        </Link>
      </div>
      <div className="stats-grid">
        {cards.map((card) => (
          <div className="stat-card" key={card.label}>
            <div className="stat-label">
              {card.label}
              <span className={`stat-icon ${card.className}`}>
                <card.icon size={17} />
              </span>
            </div>
            <strong className="stat-number">{card.value}</strong>
            <small>{card.sub}</small>
          </div>
        ))}
      </div>
      <div className="dashboard-grid">
        <section className="panel">
          <div className="panel-title">
            <div>
              <h3>Project progress</h3>
              <p className="muted">Small steps. Meaningful momentum.</p>
            </div>
            <Link to={`/org/${org.id}/projects`} className="text-link">
              All projects <ArrowRight size={14} />
            </Link>
          </div>
          {data.projects.length ? (
            <div className="project-progress">
              {data.projects.map((project) => {
                const percent = project.total
                  ? Math.round((project.completed / project.total) * 100)
                  : 0;
                return (
                  <Link
                    key={project.id}
                    to={`/org/${org.id}/projects/${project.id}`}
                  >
                    <span className="project-progress-icon">
                      <FolderKanban size={17} />
                    </span>
                    <div>
                      <strong>{project.name}</strong>
                      <small>
                        {project.completed} of {project.total} tasks complete
                      </small>
                    </div>
                    <div className="progress-track">
                      <span style={{ width: `${percent}%` }} />
                    </div>
                    <span>{percent}%</span>
                  </Link>
                );
              })}
            </div>
          ) : (
            <Empty title="Make your first move">
              <p>
                Create a project, add a task, and give your ideas a place to
                grow.
              </p>
              <Link className="button primary" to={`/org/${org.id}/projects`}>
                Explore projects
              </Link>
            </Empty>
          )}
        </section>
        <section className="panel">
          <div className="panel-title">
            <div>
              <h3>Task distribution</h3>
              <p className="muted">A healthy rhythm of work.</p>
            </div>
            <Check size={17} className="muted" />
          </div>
          {data.status.length ? (
            <>
              <div
                className="donut-chart"
                role="img"
                aria-label={data.status
                  .map((s) => `${s.name}: ${s.value}`)
                  .join(", ")}
              >
                <ResponsiveContainer width="100%" height={210}>
                  <PieChart>
                    <Pie
                      isAnimationActive={false}
                      data={data.status}
                      dataKey="value"
                      nameKey="name"
                      innerRadius={65}
                      outerRadius={88}
                      paddingAngle={5}
                    >
                      {data.status.map((s, i) => (
                        <Cell key={s.name} fill={colors[i % colors.length]} />
                      ))}
                    </Pie>
                    <Tooltip />
                  </PieChart>
                </ResponsiveContainer>
                <div className="donut-label">
                  <strong>{data.stats.totalTasks}</strong>
                  <span>Total tasks</span>
                </div>
              </div>
              <div className="chart-legend">
                {data.status.map((s, i) => (
                  <span key={s.name}>
                    <i style={{ background: colors[i % colors.length] }} />
                    {s.name.replaceAll("_", " ")}
                    <strong>{s.value}</strong>
                  </span>
                ))}
              </div>
            </>
          ) : (
            <Empty title="Room for what’s next">
              <p>Task status appears here as your projects take shape.</p>
            </Empty>
          )}
        </section>
      </div>
      {analytics && (
        <div className="dashboard-grid">
          <section className="panel">
            <h3>Workload by assignee</h3>
            <ResponsiveContainer width="100%" height={250}>
              <BarChart data={data.workload}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                <XAxis dataKey="name" fontSize={10} />
                <YAxis allowDecimals={false} fontSize={11} />
                <Tooltip />
                <Bar
                  dataKey="value"
                  name="Open tasks"
                  fill="#5b9e80"
                  radius={[5, 5, 0, 0]}
                />
              </BarChart>
            </ResponsiveContainer>
          </section>
          <section className="panel">
            <h3>Completed in the last 30 days (UTC)</h3>
            <ResponsiveContainer width="100%" height={250}>
              <AreaChart data={data.completedOverTime}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                <XAxis dataKey="name" fontSize={10} />
                <YAxis allowDecimals={false} fontSize={11} />
                <Tooltip />
                <Area
                  dataKey="value"
                  name="Completed tasks"
                  stroke="#28755c"
                  fill="#b8dacc"
                />
              </AreaChart>
            </ResponsiveContainer>
          </section>
          <section className="panel">
            <h3>Tasks by priority</h3>
            <div className="list-rows">
              {data.priority.map((p) => (
                <div className="list-row" key={p.name}>
                  <span className={"badge priority-" + p.name}>{p.name}</span>
                  <strong>{p.value}</strong>
                </div>
              ))}
            </div>
          </section>
        </div>
      )}
      <section className="panel">
        <div className="panel-title">
          <div>
            <h3>Recent activity</h3>
            <p className="muted">The little things moving your team forward.</p>
          </div>
          <Link className="text-link" to={`/org/${org.id}/activity`}>
            View all <ArrowRight size={14} />
          </Link>
        </div>
        <ActivityList items={data.activity} />
      </section>
    </>
  );
}
