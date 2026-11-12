# TeamFlow architecture

## Implementation plan

Build a modular monolith incrementally: foundation → authentication → organizations and membership → projects → tasks → board → collaboration → analytics → hardening and delivery. Each phase must have a runnable checkpoint; tenant security is validated before project/task implementation.

Java 21 / Spring Boot 3.5, PostgreSQL 17 and Flyway power the backend. React / TypeScript / Vite power the client. Maven and npm lock dependency versions. A same-origin reverse proxy serves the client and `/api`, avoiding cross-origin cookies in production. No Redis is needed for the initial single-instance application.

## Boundaries

`auth` owns accounts, opaque sessions, token rotation, password reset and verification. `organization` owns organizations, membership and invitations. `security` owns request authentication and explicit permission policies. `project`, `task`, `comment`, `activity`, `notification`, `dashboard` and `audit` own their respective business operations. Controllers use validated DTOs; services own transactions; JPA entities never form public responses.

## Authentication decision

Use random opaque bearer access tokens (15 minutes), kept only in client memory, and hashed refresh tokens in an HttpOnly cookie (7 days). Persist token digests only. Refresh families rotate under database locks; replay revokes the family and its access tokens. Logout revokes the family. Cookies require Secure in production. Refresh/logout additionally require an exact allowed Origin plus a custom header, with JSON requests and SameSite=Lax cookies; login uses the same origin guard. Password changes revoke all existing sessions. Account enabled state is read on each authenticated request. BCrypt cost is configurable. Development mail is an explicit local adapter; production requires SMTP configuration.

## Tenant isolation and permissions

Every organization API resolves the authenticated user and checks current membership. All resource reads/writes include organization IDs; nested resources also include the parent ID. Missing resources and inaccessible private projects return 404. A missing organization membership returns 403. Organization OWNER/ADMIN can manage organization projects. MEMBER can create projects and receives PROJECT_MANAGER on projects they create. Ordinary members/viewers can read organization-visible projects; private projects require membership. Organization VIEWER always caps project permissions at read-only. Project managers can manage project settings/members; contributors can manage tasks. Organization ADMIN cannot grant OWNER, modify OWNER, or change its own role. Ownership transfer is OWNER-only under an organization row lock; no operation may remove/demote the final owner.

Explicit permission enums/policies centralize these rules. Frontend capabilities are convenience controls only. Composite foreign keys prevent cross-organization project/task/assignee/member references. User identities are global; memberships are tenant-local. Invitations are expiring, hashed, single-use tokens bound to the authenticated account's email.

## Data integrity and concurrency

PostgreSQL generates UUID primary keys by default. Tenant-owned records have `(organization_id, id)` uniqueness. Task numbers use an atomic project counter under a row lock, never `max + 1`. Version predicates detect stale task updates and return 409. Board movement is transactional and stores ordering; concurrent writes must not silently overwrite. Comments, activities, notifications and security audits commit with their related changes. Task deletion is soft deletion; organization/project archival preserves records and blocks mutations. No hard-delete retention worker is enabled initially.

## Operations and risks

Flyway exclusively manages schema; Hibernate validates it. Health and metrics use Actuator; production hides OpenAPI. Correlation IDs flow through Problem Details and logs. Secrets come from environment variables. Pagination caps at 100; deterministic sorting includes an ID tie-breaker. Development rate limits are per process; multiple replicas require a shared limiter before scaling. Email and local Docker availability are external integration risks. Tests must use PostgreSQL, not H2, to establish isolation and constraint correctness.

See [database design](docs/database/README.md), [threat model](docs/architecture/THREAT_MODEL.md), and [progress](PROGRESS.md) for evidence and outstanding work.

## Persistence implementation and verified versions

Use JPA/Hibernate for accounts, and typed SQL projections through Spring JdbcClient for tenant modules. This is a deliberate hybrid inside one transaction manager, not separate storage systems. It makes tenant predicates and PostgreSQL integrity/concurrency operations explicit and avoids entity serialization and N+1 loading. Flyway owns every table.

Spring Boot 3.5.16 / Java 21 and springdoc 2.8.17 are pinned in Maven; frontend dependency resolution is committed in package-lock.json. Compatibility was checked against [Spring Boot requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [springdoc's Boot 3 documentation](https://springdoc.org/v2/) and [Vite requirements](https://vite.dev/guide/), then verified by actual builds. Runtime package registries, rather than assumed release numbers, supplied the dependency versions.

The browser serializes refresh-cookie rotations across tabs using the Web Locks API where available, in addition to a same-tab shared refresh promise. Browsers without Web Locks only get same-tab coordination. All authenticated requests consult current account/membership state; database transactions serialize membership changes and writes at the organization level. This is an initial throughput tradeoff documented for later refinement.

## Supabase database option

The user selected managed Supabase PostgreSQL while retaining Spring Boot authentication. The `supabase` profile uses a private `teamflow` schema, verified TLS, a small Hikari pool and Session/Direct connections on 5432. Flyway/JPA/JDBC agree on the schema; a profile-specific migration revokes Data API roles. Supabase Auth/client SDKs are not introduced. Backend membership checks and composite keys remain the tenant boundary; no Supabase JWT-based RLS policy is implied. See [setup and verification limits](docs/deployment/SUPABASE.md). Hosted connectivity is not verified before credentials are supplied.
