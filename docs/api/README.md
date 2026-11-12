# API and permission guide

Base: `/api/v1`. Request/response timestamps are UTC ISO-8601; due/start dates are calendar dates. OpenAPI is enabled by the `dev` profile and disabled by default in production. Never expose an unrestricted production Swagger instance.

All protected requests require `Authorization: Bearer <access token>`. Access tokens expire after 15 minutes. Refresh tokens rotate through `POST /auth/refresh` using an HttpOnly cookie. Every `/api` mutation requires `Origin` exactly matching `APP_ORIGIN`, plus `X-TeamFlow-Request: TeamFlow`. Browser fetch supplies Origin. CLI clients must explicitly set both headers. Never paste tokens into issue reports or commit them.

| Area | Routes |
|---|---|
| Identity | POST `/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/forgot-password`, `/auth/reset-password`, `/auth/verify-email`, `/auth/resend-verification`, `/auth/change-password`; GET/PATCH `/auth/me` |
| Organizations | GET/POST `/organizations`; GET/PATCH `/organizations/{org}`; POST `/{org}/archive`, `/{org}/transfer-ownership` |
| Members | GET `/organizations/{org}/members`; PATCH/DELETE `/{org}/members/{user}` |
| Invitations | GET/POST `/organizations/{org}/invitations`; DELETE `/{org}/invitations/{id}`; POST `/invitations/respond` with `{token, accept}` |
| Projects | GET/POST `/organizations/{org}/projects`; GET/PATCH `/projects/{project}`; POST `/projects/{project}/archive` |
| Project members | GET/POST `/organizations/{org}/projects/{project}/members`; DELETE `/members/{user}` |
| Tasks | GET/POST `/organizations/{org}/projects/{project}/tasks`; GET/PATCH/DELETE `/tasks/{task}`; PATCH `/tasks/{task}/move` (or `/status`) |
| My tasks/search | GET `/organizations/{org}/tasks?assignee={user}&status=TODO&priority=HIGH&overdue=true` |
| Comments | GET/POST `.../tasks/{task}/comments`; PATCH/DELETE `/comments/{id}` |
| Activity | GET `/organizations/{org}/activity`, `/projects/{project}/activity`, `/projects/{project}/tasks/{task}/activity` |
| Dashboards | GET `/organizations/{org}/dashboard`, `/projects/{project}/dashboard` |
| Notifications | GET `/organizations/{org}/notifications`; PATCH `/notifications/{id}/read`, `/notifications/read-all` |
| Audit | GET `/organizations/{org}/audit` (Owner/Admin) |

Paths abbreviated in a row retain their organization and project prefixes. The generated OpenAPI is the exact route/DTO contract.

## Organization permissions

| Action | Owner | Admin | Member | Viewer |
|---|---|---|---|---|
| Read organization/member list | Yes | Yes | Yes | Yes |
| Manage organization/invitations | Yes | Yes | No | No |
| Change member roles | Yes, preserving final owner | Cannot modify Owner or own role | No | No |
| Transfer/archive organization | Yes | No | No | No |
| Create project | Yes | Yes | Yes | No |
| Read organization-visible project | Yes | Yes | Yes | Yes |
| Read private project | Yes | Yes | Project membership | Project membership |
| Manage project | Yes | Yes | Project Manager | No |
| Manage tasks/comment | Yes | Yes | Project Manager/Contributor | No |
| Read analytics/activity | All tenant projects | All tenant projects | Accessible projects | Accessible projects |

Project creators become Project Managers. Assignees must be contributing project members and cannot be organization Viewers. Removing a member unassigns active tasks and advances their versions. Project keys are immutable to keep task identifiers stable. Any project manager can administer project membership; organization Viewers remain read-only even when assigned a Project Manager role.

Comment authors can edit their own comments. Authors and organization administrators with write access may delete/moderate comments. Rich HTML is not accepted/rendered; text is escaped by React.

## Pagination, concurrency and errors

Paginated results are `{items,total,page,size}`. Pages are zero-based; sizes clamp to 1–100. All lists have deterministic tie-breakers. Project filtering supports `search`, `status`, `visibility`, `sort=name|dueDate|createdAt`; task filtering supports `search`, `status`, `priority`, `assignee`, `overdue`, `sort=title|dueDate|updatedAt|position`. Unknown sort names use the default.

Task PATCH requires the current `version`. DELETE requires `?version=...`. Movement requires `{status,beforeId?,version}`; omit `beforeId` to append. Shifted cards also advance versions. A stale write returns 409; refetch before resubmitting. The board rolls back failed optimistic changes and reconciles with server state.

Errors use Problem Details with `status`, `title`, `detail`, `instance`, `requestId`, and validation `errors` when applicable. Anonymous/expired sessions return 401, missing membership returns 403, missing/inaccessible private resources return 404, invalid relationships return 400, constraint/stale-write conflicts return 409, throttling returns 429, and mail failure returns 503. Internal stack traces and SQL are never returned.
