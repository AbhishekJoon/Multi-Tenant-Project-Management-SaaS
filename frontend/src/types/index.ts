export type OrgRole = "OWNER" | "ADMIN" | "MEMBER" | "VIEWER";
export interface User {
  id: string;
  name: string;
  email: string;
  emailVerified: boolean;
}
export interface Organization {
  id: string;
  name: string;
  description: string;
  logoUrl: string | null;
  role: OrgRole;
  archivedAt: string | null;
}
export interface Member {
  userId: string;
  name: string;
  email: string;
  role: OrgRole;
  joinedAt: string;
}
export interface Invitation {
  id: string;
  email: string;
  role: OrgRole;
  status: string;
  expiresAt: string;
}
export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}
export type ProjectRole = "PROJECT_MANAGER" | "CONTRIBUTOR" | "VIEWER";
export type Priority = "LOW" | "MEDIUM" | "HIGH" | "URGENT";
export type TaskStatus =
  "TODO" | "IN_PROGRESS" | "IN_REVIEW" | "BLOCKED" | "DONE";
export interface Project {
  id: string;
  organizationId: string;
  name: string;
  projectKey: string;
  description: string;
  status: string;
  visibility: string;
  priority: Priority;
  startDate: string | null;
  dueDate: string | null;
  createdAt: string;
  projectRole: ProjectRole | null;
}
export interface ProjectMember {
  userId: string;
  name: string;
  email: string;
  role: ProjectRole;
}
export interface Task {
  id: string;
  organizationId: string;
  projectId: string;
  identifier: string;
  taskNumber: number;
  title: string;
  description: string;
  status: TaskStatus;
  priority: Priority;
  reporterId: string;
  assigneeId: string | null;
  assigneeName: string | null;
  dueDate: string | null;
  estimatedEffort: number | null;
  position: number;
  version: number;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}
export interface Activity {
  id: string;
  projectId: string;
  taskId: string | null;
  actorName: string;
  action: string;
  detail: string;
  createdAt: string;
}
export interface Notification {
  id: string;
  message: string;
  projectId: string | null;
  readAt: string | null;
  createdAt: string;
}
export interface Comment {
  id: string;
  authorId: string;
  authorName: string;
  body: string;
  createdAt: string;
  updatedAt: string;
}
export interface Distribution {
  name: string;
  value: number;
}
export interface Dashboard {
  stats: {
    totalProjects: number;
    totalTasks: number;
    completedTasks: number;
    inProgressTasks: number;
    blockedTasks: number;
    overdueTasks: number;
    members: number;
    myTasks: number;
  };
  status: Distribution[];
  priority: Distribution[];
  workload: Distribution[];
  completedOverTime: Distribution[];
  projects: { id: string; name: string; total: number; completed: number }[];
  activity: Activity[];
}
