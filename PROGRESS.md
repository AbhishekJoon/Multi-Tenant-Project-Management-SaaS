# TeamFlow implementation progress

Branch: `Test`. Initial repository contained only README.md. User changes: none at inspection.

## Checklist

- [x] Phase 0: repository inspection, architecture, authorization policy, initial schema design and roadmap.
- [x] Phase 1: Spring Boot, React, PostgreSQL, Flyway, health, Docker and runnable builds.
- [x] Phase 2: authentication, refresh rotation/replay detection, logout, profile and security tests.
- [x] Phase 3: organizations, memberships, invitations, ownership and tenant isolation tests.
- [x] Phase 4 core: projects and private-project authorization; advanced filters remain below.
- [x] Phase 5 core: task lifecycle, assignment, counters, basic filters and optimistic concurrency; date-range filters remain below.
- [x] Phase 6: persisted Kanban, keyboard handles and ordering; extended accessibility audit remains below.
- [ ] Phase 7 complete scope: comments/activity/audit and task notifications work; invitation notices remain below.
- [ ] Phase 8 complete scope: scoped dashboards/database aggregates/charts work; broader analytics filters remain below.
- [ ] Phase 9: responsive UI, themes, forms and accessibility.
- [ ] Phase 10: security audit, dependency audit and full automated validation.
- [ ] Phase 11 complete scope: Docker/CI configuration/deployment and backup docs delivered; external release/restore drill and remote CI remain unverified.
- [x] Phase 12 local artifacts: README, diagrams, API/deployment/security docs, screenshots and verification report. Public demo is not deployed.

## Environment and commands

Inspection: `git status --short --branch`, `git branch --list`, `rg --files`, `java -version`, `node --version`, `npm --version`, `docker version`. Java 21 and Node 24 are available. Maven is not on PATH. Docker CLI exists but daemon was stopped. No existing application or tests.

## Current work

Core MVP is implemented and verified. Final browser rerun passed. No external deployment has been performed. Resume from the remaining-work section, not from repository initialization.

## Checkpoint: identity and tenant foundation

Created backend Maven/Java 21 project, Flyway identity/membership schema, authentication, permission policies, organization and invitation APIs; React typed API client, auth forms, workspace selector, reusable form/modal components and design tokens. Added PostgreSQL/Mailpit Compose services and initial Dockerfiles.

Validation: `backend/mvnw.cmd -B -ntp test` passed **9 tests, zero failures/skips**, using Testcontainers PostgreSQL 17. Verified migration initialization, health, anonymous rejection, login, disabled-account rejection, refresh rotation, replay family revocation, logout, two-organization membership, differing roles, invitation email binding/replay rejection, final-owner protection and admin escalation denial. `npm run build`, `npm test` (**5 tests**) and `npm run lint` passed. Docker Desktop started; PostgreSQL and Mailpit containers are running. Maven wrapper bootstrap and a test helper naming collision were fixed.

Remaining foundation acceptance: full Compose application startup and completed organization management UI. Next: private projects, task integrity/concurrency, then Kanban and collaboration. Tenant foundation is validated before implementing these modules. No public deployment.

## Checkpoint: projects, tasks, board, collaboration and analytics

Implemented tenant-aware project management and private-project membership; task creation/editing/assignment, atomic counters, soft deletion, pagination/filtering, explicit version conflicts and transactionally ordered board movement; comments, structured activity, notification persistence, audit queries and database aggregates. Added composite foreign keys and trigram indexes in V2. React routes now cover landing/auth, workspace selection, organization/team/settings/profile, projects, task lists/details/board, notifications/activity/audit and dashboard/analytics. All core actions call real APIs. Forms use React Hook Form/Zod; modals use Radix; notifications use Sonner; charts use Recharts; boards use dnd-kit.

Backend testing progressed from 9 to 18 to **21 tests**. The additional tests verify private project visibility, nested tenant/parent combinations, foreign-assignee rejection at API and database levels, stale task/board updates, concurrent task numbering, actual simultaneous update conflicts, read-only role caps, comments, scoped notifications/aggregates, soft deletion, archival, password recovery/verification, SMTP rollback and authentication throttling. A trend-query GROUP BY ambiguity was found and fixed. Backend `verify` builds the executable jar and passes security-package coverage gates (90% lines / 70% branches).

Frontend `npm run lint`, `npm run typecheck`, `npm run test:coverage`, `npm run build` pass. **9 frontend tests** cover form validation/server errors, permission rules, board loading/errors/read-only controls and keyboard drag handles. Gate coverage is limited to shared forms/permission functions: approximately 91% lines / 78% branches. It is not whole-app coverage. `npm audit --audit-level=high` reports zero vulnerabilities in the resolved npm dependency graph.

## Checkpoint: Docker and real integration

`docker compose up -d --build` successfully starts frontend (Nginx), backend, PostgreSQL 17 and Mailpit. Backend health is UP. A separate `teamflow-e2e` Compose project has its own PostgreSQL volume/network and ports 5174/8081/5433/8026, protecting the development workspace from test data.

The first full browser run found an ambiguous test locator (Project name vs Search project names); the selector was fixed. **Both Playwright journeys then passed** against real APIs and SMTP: register, create organization, invite, second-account acceptance, private project denial, project membership, task assignment, pointer drag to Done, persistence after reload, comments, notifications, activity, mobile navigation, cross-organization denial, password reset and reset-link replay denial. No E2E API responses were mocked. A final rerun expands email verification, login/reload and organization switching and recaptures deterministic screenshots.

Docker's initial Maven `dependency:go-offline` step resolved unnecessary plugin/database metadata; the Dockerfile now builds directly with a Maven cache mount. Docker application builds and health checks subsequently passed. Java and frontend sources were formatted for review. Secret-bearing DTO string representations are redacted, mail operations have timeouts, logout/reset use account locks, and refresh rotation coordinates browser tabs via Web Locks where supported.

## Remaining work and release limits

- Advanced project manager/date and task date-range filters; the current persisted filters are listed exactly in docs/api/README.md.
- Global invitation inbox and pre-membership in-app invitation notifications. Email invitation accept/decline/revoke works; assignment/comment/status notifications work.
- Self-service archive recovery and scheduled retention/purge; archived records currently remain indefinitely. Disabled accounts are enforced server-side and tested; global disablement administration UI is not implemented.
- Broader organization/project/task form and optimistic-rollback unit coverage, cross-browser/screen-reader audits, load/query-plan benchmarks and a Java dependency CVE scan. Current integration coverage is stronger than unit breadth.
- Shared rate limiting before multiple replicas; transactional email outbox; session-history cleanup; metrics exporter and hosted error reporting configuration.
- Production hosting, HTTPS, production SMTP credentials, managed database backups/restore drill and remote GitHub Actions execution. CI is configured but no GitHub run/public deployment is claimed.

No unresolved failing automated check is accepted as complete. Core MVP acceptance and the original broader roadmap are distinguished in docs/VERIFICATION.md. Next recommended implementation step: close the advanced filtering/invitation inbox gaps with tenant-scoped integration tests, then execute production security/load and backup-recovery gates before an external release.

## Final local verification checkpoint

- Backend `mvnw -B -ntp verify`: **21 passed**, zero failures/errors/skips; executable jar built; security coverage gates passed. Security package: 142/148 lines (95.9%), 55/71 branches (77.5%).
- Frontend lint, type checking, production build and **9 tests** passed. Shared form/permission coverage: 91.3% lines, 77.58% branches; npm audit reported zero vulnerabilities.
- Expanded Playwright rerun: **2 passed in 22 seconds**, including email verification, login using the reset password, session refresh after reload and switching between two authorized organizations, alongside the full collaboration/board/isolation workflow.
- Development Compose rebuilt from final application sources; all four services healthy. Application: http://localhost:5173; development email: http://localhost:8025. The isolated test stack used ports 5174/8026 and a separate database volume.
- Desktop/mobile screenshots visually inspected; chart and drawer timing artifacts corrected. Sensitive generated files/test traces are ignored. A source pattern scan found no private keys or recognizable API credentials; a random database password lives in the ignored local `.env`.
- Architecture/API/ER/threat/deployment/backup/demo documentation and docs/VERIFICATION.md reflect actual scope and limitations. GitHub CI exists but has not run remotely. No external deployment or messages to real recipients.

Local changes are organized into backend, frontend and documentation/CI commits on `Test`. No remote push was performed. The broader incomplete roadmap remains above; this checkpoint is not full production-release approval.

## Runtime preference: native Windows development

The user requested local processes and deferred Docker. All TeamFlow Compose services were stopped, and their database was backed up to the ignored `.tools/native/teamflow-backup.sql` before shutdown. The original Docker volume remains intact. Do not restart Docker services for normal development without a new request.

Installed portable PostgreSQL 17.11 from the official PostgreSQL/EDB Windows binary distribution and Mailpit 1.33.1 from its official release (SHA256 verified) inside `.tools/native`. Initialized a loopback-only PostgreSQL cluster and restored the existing database/migration history. No system-wide service installation or committed credentials.

Added `scripts/start-local.ps1` and `scripts/stop-local.ps1`, with hidden background processes, saved PID/executable/creation-time checks, temporary password-file cleanup, environment restoration and readiness checks. Java runs the existing backend jar on 8080; Node runs Vite on 5173; native PostgreSQL uses 5432; native Mailpit uses 1025/8025. Vite API targets use 127.0.0.1 to match the backend's explicit IPv4 loopback binding. See docs/deployment/LOCAL_WINDOWS.md.

Observed native checks: frontend HTTP 200, backend health UP, Vite-to-backend health proxy UP and Mailpit readiness HTTP 200. Startup testing caught and fixed PowerShell process-tree waiting and nested JSON-array enumeration issues. The complete start/stop/restart cycle passed: all five local ports closed on stop, then readiness checks passed after restart with data retained. `docker compose ps --status running` returned no TeamFlow containers. Frontend lint/type checking passed with `npm.cmd` (the shell blocks npm.ps1 under its execution policy). Docker/Testcontainers suites are deferred in this mode.

## Supabase database preparation

The user selected **Supabase PostgreSQL with existing Spring Boot authentication**, and will provide connection details later. Added a `supabase` profile with a five-connection Hikari pool (one idle), bounded network timeouts, certificate/hostname verification, a private `teamflow` schema shared by JPA/JDBC/Flyway and Session/Direct connections on 5432. The environment postprocessor rejects missing configuration, credential-bearing/query-parameter URLs, transaction-pooler ports and unsafe schema/TLS overrides before connecting.

Added a reviewed bootstrap SQL script and a profile-only V3 grants migration. Existing V1/V2 checksums are unchanged; local public-schema databases are unaffected. Private application schema and privileges exclude Supabase `anon`, `authenticated` and `service_role` access. Tenant enforcement remains Spring Boot policies and database constraints, not a claimed Supabase JWT/RLS implementation. No frontend keys/SDK, Auth replacement, external database creation or hosted migration has occurred.

`.env.supabase.example` documents the database credentials and CA certificate; ignored `.env.supabase.local` is ready for real values. Updated native scripts support `-Database Supabase` without starting/restoring a local database, while the default Local mode keeps the existing app running. Deployment instructions are in docs/deployment/SUPABASE.md.

Implemented native PostgreSQL backend testing using randomly named `teamflow_test_*` databases; the harness rejects hosted/non-test URL targets. The private-schema run passed **29 tests, zero failures/errors/skips**, including migrations, original auth/tenant/concurrency tests, API-role denial/default grants, and five profile/security configuration tests. Its fixture deliberately grants unsafe API access before migration, proving V3 removes it. Public-schema regression passed **26 tests**, with the three private-only cases intentionally skipped. Both security coverage gates passed. Temporary databases and newly created NOLOGIN fixtures were removed. CI now includes both schema modes; remote CI has not run.

The executable package builds successfully. Packaged Supabase mode rejects missing credentials before database initialization, and native Supabase preflight rejects an absent certificate while leaving independent local PostgreSQL running. Corrected a startup race by recording known executable paths and waiting for confirmed process exit before packaging; unsafe/unmatched process records are retained. The updated local start/stop/start cycle passed and the app remains at http://localhost:5173 using native PostgreSQL. Docker was not started. Actual Supabase SSL/pooler authentication/network/dashboard settings remain unverified until credentials/certificate are supplied.

## Supabase connection activated — 12 November 2026

The user supplied the Session pooler details and corrected the saved database password. The verified-TLS login now succeeds. Client connection inspection confirmed TLS 1.3; `pg_stat_ssl` queried through a pooler describes its server-side connection and was not used as evidence of the client's encryption.

Executed the prepared bootstrap against the dedicated application schema, then started the native app with `-Database Supabase` (`dev,supabase` profiles). Flyway applied V1–V3 successfully in `teamflow`; hosted verification reports 3 successful migrations and 15 schema tables. Supabase `anon`, `authenticated` and `service_role` roles have neither private-schema USAGE nor SELECT on the application's users table. No application rows were seeded or copied from the local database. The original local database is retained, its server is stopped, and Docker remains deferred.

Frontend HTTP 200, backend/through-Vite health UP, and an unknown bearer token is rejected with 401 after the real JDBC authentication lookup. Current local address: http://localhost:5173; email remains local Mailpit on 8025. Restart using `scripts/start-local.ps1 -Database Supabase -SkipBuild`; selecting Local explicitly returns to the preserved native database. Real credentials and the CA certificate remain ignored; no passwords or connection secrets were committed.

Dashboard Data API exposed-schema settings were not inspected. Database grants enforce the API-role boundary; keep `teamflow` excluded from the Data API as documented. Hosted user lifecycle/E2E journeys were not seeded into the normal database; the existing 29-case private-schema suite and hosted connection/migration/authorization smoke checks are separate evidence. External application deployment, production email and the remaining release gates are still pending.

## Supabase Auth activated — 13 November 2026

The user requested Supabase login in addition to the database. Supabase mode now delegates signup, password login, refresh, PKCE email confirmation/recovery, profile/password updates and logout to Supabase Auth through Spring Boot. Access tokens stay in memory, refresh/verifier/recovery cookies are HttpOnly, and current provider sessions plus application account state are checked. Tenant roles still come from application memberships. V4 preserves application IDs and adds a unique provider subject; verified-email adoption removes legacy credentials/tokens. Local mode remains available for offline development.

Retrieved the existing project's URL and public publishable key through the connected Supabase tools and saved them in the ignored local environment file. No signing/admin secret was requested. Verified Email authentication is enabled and auto-confirm is disabled. The native app was rebuilt and started with dev,supabase; frontend and backend health return 200, and a live unknown-account login returns 401 without creating users or sending emails. Supabase reports four successful Flyway migrations, zero application users, and no private-schema access for the authenticated browser role. Security advisors returned no findings. Docker was not used.

Final private-schema PostgreSQL suite: 36 tests passed, zero failures/errors/skips, with security coverage gates passing. Public-schema reports: 33 passed and three private-only cases skipped. Frontend: 12 tests passed, lint/types and production build passed. API tests now isolate the mocked email service from the developer's SMTP health check. Full valid-user hosted signup/login/recovery journeys are still unverified. Dashboard callback allowlisting requires the URL Configuration step documented in docs/deployment/SUPABASE.md; connected tools cannot edit it. Latest Auth changes remain uncommitted on Test; nothing was pushed. Broader production-release gaps above remain open.
