import type { OrgRole } from "../types";
export const managesOrg = (role: OrgRole) =>
  role === "OWNER" || role === "ADMIN";
export const createsProjects = (role: OrgRole) => role !== "VIEWER";
export const managesTasks = (role: OrgRole, projectRole: string | null) =>
  role !== "VIEWER" &&
  (managesOrg(role) ||
    projectRole === "PROJECT_MANAGER" ||
    projectRole === "CONTRIBUTOR");
