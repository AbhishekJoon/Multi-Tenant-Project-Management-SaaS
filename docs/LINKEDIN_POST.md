# LinkedIn project post

Copy the post below and attach `docs/screenshots/workspace-desktop.png`. The screenshot uses synthetic test data. This draft describes the current MVP; it does not announce a public deployment.

---

I've been building TeamFlow, a multi-tenant project management SaaS, and the working MVP is now on GitHub.

The app brings together workspaces, team invitations, role-based permissions, private projects, tasks, a drag-and-drop Kanban board, comments, notifications and dashboards.

Stack: Java 21, Spring Boot, React, TypeScript, PostgreSQL and Supabase Auth.

The most interesting part has been tenant isolation: one person can belong to multiple organizations with different roles, and every request needs to respect the current organization and project permissions. The implementation combines backend authorization, tenant-scoped queries and database constraints. It also handles concurrent task updates and persists board changes.

Local verification includes 36 backend tests in the private-schema configuration and 12 frontend tests. Supabase Auth is integrated; a full hosted signup/recovery walkthrough and further production hardening are still on the roadmap.

Code and setup instructions:
https://github.com/AbhishekJoon/Multi-Tenant-Project-Management-SaaS/tree/Test

I'd welcome feedback on the architecture, permissions model and developer experience.

#Java #SpringBoot #React #TypeScript #PostgreSQL #Supabase #SaaS #FullStackDevelopment
