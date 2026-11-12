# Supabase PostgreSQL with the Spring Boot backend

The chosen architecture is React → Spring Boot → Supabase PostgreSQL. Spring Boot continues to own registration, login, refresh/recovery, organizations and tenant/project authorization. Supabase Auth, client-side database requests, Storage and Realtime are not enabled by this change. No Supabase JavaScript SDK or browser API keys are needed.

## Connection details to supply

Copy `.env.supabase.example` to the ignored `.env.supabase.local` and fill in:

| Variable | Value |
|---|---|
| `SUPABASE_DB_URL` | `jdbc:postgresql://<host>:5432/postgres`, without username/password/query parameters |
| `SUPABASE_DB_USER` | Username from **Connect → Session pooler**, usually `postgres.<project-ref>` |
| `SUPABASE_DB_PASSWORD` | The project's PostgreSQL database password |
| `SUPABASE_DB_SSL_ROOT_CERT` | Path to the CA certificate downloaded from database SSL settings |
| `SUPABASE_DB_POOL_SIZE` | Default 5 backend connections per application instance |
| `SUPABASE_DB_MIN_IDLE` | Default 1 idle connection |

The Supabase project URL and publishable/anon/service-role API keys are different from PostgreSQL connection credentials and cannot replace them. Keep the database password in the ignored environment file or a server secret manager. It must not become a `VITE_*` variable. Certificate files can live in the ignored `.secrets` directory; the default template expects `.secrets/supabase-ca.crt`.

Use the **Session pooler on port 5432** for this long-running Spring Boot/Hibernate application. A Direct connection on 5432 also works if the host/network has the necessary IP connectivity. The transaction pooler on 6543 is deliberately rejected because this application uses persistent session settings, prepared statements and Flyway. See [Supabase's Spring Boot connection guide](https://supabase.com/docs/guides/getting-started/quickstarts/spring-boot).

The profile sets `sslmode=verify-full` and `sslrootcert` separately from the URL, verifying the certificate and hostname. The dashboard CA download must match the selected endpoint. Connection attempts fail rather than falling back to plaintext. See [Supabase connection/TLS guidance](https://supabase.com/docs/guides/database/connecting-to-postgres).

## One-time database preparation

Use a fresh/dedicated Supabase project or review the target before applying changes. In its SQL Editor, run the exact script [backend/supabase/bootstrap.sql](../../backend/supabase/bootstrap.sql). It:

1. Creates a private `teamflow` schema, separate from Supabase's `public`, `auth` and `storage` schemas.
2. Installs `pg_trgm` in `extensions` if missing, for the existing indexed searches.
3. Rejects an incompatible existing extension location instead of moving another application's extension.
4. Removes schema usage/default table privileges for PUBLIC and the `anon`, `authenticated` and `service_role` Data API roles when they exist.

Run bootstrap and Flyway with the same owning database role (the dashboard's `postgres` role and the Session pooler's `postgres.<project-ref>` username refer to the same database role). If you later separate migration/runtime roles, grant the runtime role only the required privileges on `teamflow`, keep the API-role exclusions, and validate the new role arrangement before rollout.

Keep `teamflow` **out of the Data API exposed-schema list**. If this project is used only through Spring Boot, disable the Data API in the Supabase dashboard. Do not disable it on an unrelated shared project without reviewing that project's consumers. [Supabase documents private schemas, grants and Data API configuration](https://supabase.com/docs/guides/api/securing-your-api).

We do not install misleading `auth.uid()` tenant policies: the application uses Spring Boot's own opaque sessions rather than Supabase JWTs. Tenant rules remain the existing tested backend checks and composite constraints. Private schema permissions block browser/API roles from bypassing them; they do not restrict an administrator or a compromised backend database credential.

## Start locally with the hosted database, without Docker

After supplying real credentials/certificate and completing bootstrap:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/stop-local.ps1
powershell -ExecutionPolicy Bypass -File scripts/start-local.ps1 -Database Supabase
```

This builds the backend, selects `dev,supabase`, and starts Java/Vite/local Mailpit. It does not start or initialize local PostgreSQL, restore a local dump, or connect the browser directly to Supabase. Stop it with `scripts/stop-local.ps1`; the script cannot stop the hosted database. It also leaves an independently running local cluster untouched in Supabase mode.

On first startup, Flyway applies the original V1/V2 migrations **inside `teamflow`** and the profile-only V3 grants restriction. Hibernate validates its account mapping in that schema; JDBC also uses `teamflow,extensions`. Flyway does not create the schema automatically or baseline an existing unmanaged schema. Existing local databases remain in `public` and keep their existing Flyway history. No existing public data is automatically moved or uploaded. Plan a separate reviewed data migration if that data must transfer.

The native local database remains the default until this explicit switch. Return with `scripts/start-local.ps1 -Database Local`. Docker stays deferred.

## Hosted backend configuration

For deployment, activate `supabase` without `dev`, supply the `SUPABASE_DB_*` variables plus production SMTP configuration, set the HTTPS `APP_ORIGIN`, and keep `SECURE_COOKIES=true`. Provide the CA file as a read-only mounted file. No credentials are baked into images.

The Hikari pool is intentionally small (5 maximum / 1 minimum); it has bounded connect/socket timeouts, 10-minute connection lifetime and keepalive. It is a starting point, not an asserted Supabase plan limit. Size the sum of pools across all instances to the actual project's connection allowance, and measure latency/load before increasing it. The transaction and membership locking behavior stays unchanged. The existing rate limiter remains single-instance.

## Verification before connecting the real project

```powershell
# Uses a randomly named disposable database on the native local PostgreSQL instance.
powershell -ExecutionPolicy Bypass -File scripts/test-backend-local.ps1 -PrivateSchema
```

This runs the backend suite against private-schema migrations, tests API-role permission denial (including newly created tables), and checks configuration guards. It creates/removes NOLOGIN role fixtures only when they did not already exist, and removes only the randomly named test database. The test harness refuses external URLs and non-test database names. It does not run Docker and never targets Supabase or the development database. The default CI Testcontainers suite remains available separately.

Local results cannot establish hosted network access, real certificate trust, pooler authentication or dashboard API settings. Those checks remain pending until the project's connection details are supplied. Run a hosted health/auth/tenant-isolation smoke test with controlled accounts after connecting; do not represent the local simulation as a verified Supabase deployment.
