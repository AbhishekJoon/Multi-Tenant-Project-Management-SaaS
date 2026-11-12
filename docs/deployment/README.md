# Deployment and operations

The Compose configuration is for local development. Production requires infrastructure and credentials chosen and supplied by the operator; no external deployment has been performed.

## Production topology

Serve the frontend and `/api` on one HTTPS origin through Nginx or a managed ingress. Run the Java 21 image behind the proxy on a private network. Use a supported managed PostgreSQL instance with TLS, restricted network access, automated backups and a separate least-privilege application identity. Run Flyway with a migration-capable identity during release if the application identity cannot create extensions/tables.

`pg_trgm` is required for indexed project/task substring search. Verify the database provider permits it. Configure capacity and price with the chosen provider; this documentation makes no free-hosting claim. Keep the application at one replica until rate limiting is backed by a shared store. No Redis cache is currently required.

## Environment

| Variable | Production requirement |
|---|---|
| `DATABASE_URL` | JDBC PostgreSQL URL, including appropriate TLS options |
| `DATABASE_USER`, `DATABASE_PASSWORD` | Inject through the platform secret manager |
| `APP_ORIGIN` | Exact public HTTPS origin, without trailing slash |
| `SECURE_COOKIES` | `true` |
| `SPRING_PROFILES_ACTIVE` | Do not enable `dev` |
| `OPENAPI_ENABLED` | `false` unless separately protected |
| `SMTP_HOST`, `SMTP_PORT` | Real transactional email provider |
| `SMTP_USER`, `SMTP_PASSWORD` | Provider credentials from secrets |
| `SMTP_AUTH`, `SMTP_STARTTLS` | Usually `true`; match the provider |
| `MAIL_FROM` | Verified sender identity |
| `BCRYPT_COST` | Default 12; benchmark before increasing |

The local Compose manifest forwards email variables. For a managed deployment set backend environment variables directly. If adapting Compose for production, remove public database/Mailpit/backend ports and replace the frontend HTTP port with an HTTPS ingress. Mailpit must not be publicly exposed. The dev profile deliberately allows non-Secure localhost cookies; it is not a production configuration.

Nginx replaces client-supplied forwarding headers; Tomcat's native forwarding support trusts internal proxy addresses. The backend must be reachable only through your trusted proxy. Configure proxy trust explicitly if deploying into a different network topology. Do not allow arbitrary external clients to reach a backend that trusts forwarded addresses, because rate-limit attribution would be spoofable.

## Release and smoke checks

1. Build and run all CI checks and isolated browser journeys.
2. Back up PostgreSQL and test restoration before a schema release.
3. Apply forward-only Flyway migrations; never edit an applied version.
4. Deploy backend and frontend from the same tested commit.
5. Verify `/actuator/health` returns UP; confirm registration email, login/refresh/logout, project/task writes and cross-tenant rejection using dedicated test accounts.
6. Confirm HTTPS cookies have Secure/HttpOnly/SameSite flags, OpenAPI is disabled, database ports are private and application logs contain no credentials.

Health responses reveal no database details. Actuator exposes health/info only; add authenticated/internal metric scraping when integrating a monitoring provider. Micrometer records standard JVM/HTTP/pool metrics. Correlation IDs appear in `X-Request-ID`, Problem Details and structured key/value logs. Preserve those IDs in the edge proxy and support tooling; do not log request bodies or tokens. Alert on sustained 5xx, failed health, pool exhaustion, mail failures, disk usage and repeated authorization denials.

## Backup and restore

Run from a secure operator terminal. These example commands use PostgreSQL tools inside the running Compose database and do not embed passwords.

```bash
docker compose exec -T db pg_dump -U teamflow -d teamflow -Fc > teamflow.backup
# Restore only into an explicitly chosen empty recovery database:
docker compose exec -T db createdb -U teamflow teamflow_recovery
docker compose exec -T db pg_restore -U teamflow -d teamflow_recovery --no-owner < teamflow.backup
```

Use a binary-safe shell for custom-format dumps: Bash or PowerShell 7.4+. Windows PowerShell 5 redirection may corrupt binary output; alternatively run pg_dump to a container file and use `docker compose cp db:/tmp/teamflow.backup ./teamflow.backup`. Encrypt backups, restrict access, keep copies outside the application host and test point-in-time recovery regularly. Use your configured database username/name if different.

Tasks are soft-deleted and excluded from normal APIs. Organization/project archival retains records and prevents writes. No automatic purge or archive restoration exists yet. Define legal/product retention requirements and build tested recovery controls before adopting this application for sensitive production workloads.

## Known operational limits

SMTP delivery is synchronous and not backed by an outbox. A provider can accept email just before a database transaction fails; monitor and retry intentionally. Refresh history is retained for replay detection; schedule a carefully scoped token cleanup policy before high-volume use. Password recovery responses are generic, but full timing-equalization and distributed abuse controls are future hardening work. Organization writes serialize under a row lock; measure contention before scaling write-heavy tenants.
