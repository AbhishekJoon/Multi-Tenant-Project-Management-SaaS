# Supabase PostgreSQL and Auth with Spring Boot

React calls Spring Boot, which delegates signup, login, refresh and recovery to Supabase Auth and stores application data in Supabase PostgreSQL. Spring Boot retains tenant/project authorization. Access tokens stay in browser memory and refresh tokens in an HttpOnly cookie. No direct browser database access, client SDK, Storage or Realtime is introduced.

## Authentication configuration

Set `SUPABASE_URL` to the HTTPS project origin and `SUPABASE_PUBLISHABLE_KEY` to its public API key in `.env.supabase.local`. A legacy anon key also works; secret/service-role keys are rejected. These are additional to the PostgreSQL credentials below.

In Supabase → Authentication → URL Configuration, set the local Site URL to `http://localhost:5173` and allow `http://localhost:5173/**` for development. Production should allow only the exact callbacks `https://<app>/auth/callback?flow=signup` and `https://<app>/auth/callback?flow=recovery`, matching HTTPS `APP_ORIGIN`. Keep Email authentication enabled and default confirmation/recovery templates using `{{ .ConfirmationURL }}`. Open links in the same browser/device where requested; verifier cookies expire after one hour. Confirmation resend sends a PKCE email sign-in link for existing accounts, with account creation disabled. See [Supabase password Auth](https://supabase.com/docs/guides/auth/passwords) and [PKCE](https://supabase.com/docs/guides/auth/sessions/pkce-flow).

Supabase sends authentication emails; Mailpit still receives workspace invitations locally. Production needs Auth SMTP configured in Supabase separately from invitation SMTP. Legacy passwords are not copied into Supabase. Existing users create/confirm a Supabase account with the same email, or recover an existing Supabase account; first verified login preserves application IDs/memberships and removes legacy credentials. Linked email changes need a separate account migration. User metadata never grants roles.

The backend validates each token with Supabase `/auth/v1/user`, then checks project/subject/audience/role/expiry claims and `auth.sessions`/`auth.users` metadata for revocation, expiry, bans and deletion. It never writes managed auth tables. A separate runtime DB role needs read access to `auth.sessions(id,user_id,not_after)` and `auth.users(id,deleted_at,banned_until)` in addition to application privileges. Provider outages fail closed; authenticated requests incur provider latency and no validation cache is enabled. No signing secret or admin API key is needed.

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

Tenant rules remain backend membership checks and composite constraints. JDBC does not inherit the browser JWT's `auth.uid()` context. Private schema permissions block browser/API roles from bypassing the backend; they do not restrict an administrator or compromised backend database credential.

## Start locally with the hosted database, without Docker

After supplying real credentials/certificate and completing bootstrap:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/stop-local.ps1
powershell -ExecutionPolicy Bypass -File scripts/start-local.ps1 -Database Supabase
```

This builds the backend, selects `dev,supabase`, and starts Java/Vite/local Mailpit. It does not start or initialize local PostgreSQL, restore a local dump, or connect the browser directly to Supabase. Stop it with `scripts/stop-local.ps1`; the script cannot stop the hosted database. It also leaves an independently running local cluster untouched in Supabase mode.

Flyway applies V1/V2 **inside `teamflow`**, profile-only V3 grants restrictions and V4's Supabase identity mapping. Existing migrations remain unchanged. Hibernate validates its account mapping; JDBC uses `teamflow,extensions`. Flyway does not create schemas automatically or baseline unmanaged data. Existing local data stays in `public` and is not uploaded automatically.

The native local database remains the default until this explicit switch. Return with `scripts/start-local.ps1 -Database Local`. Docker stays deferred.

## Hosted backend configuration

For deployment, activate `supabase` without `dev`, supply database/Auth variables plus invitation SMTP settings, set HTTPS `APP_ORIGIN`, and keep `SECURE_COOKIES=true`. Configure Auth email delivery separately in Supabase. Provide the CA file as a read-only mounted file. No credentials are baked into images.

The Hikari pool is intentionally small (5 maximum / 1 minimum); it has bounded connect/socket timeouts, 10-minute connection lifetime and keepalive. It is a starting point, not an asserted Supabase plan limit. Size the sum of pools across all instances to the actual project's connection allowance, and measure latency/load before increasing it. The transaction and membership locking behavior stays unchanged. The existing rate limiter remains single-instance.

## Verification before connecting the real project

```powershell
# Uses a randomly named disposable database on the native local PostgreSQL instance.
powershell -ExecutionPolicy Bypass -File scripts/test-backend-local.ps1 -PrivateSchema
```

This runs the backend suite against private-schema migrations, tests API-role permission denial (including newly created tables), and checks configuration guards. It creates/removes NOLOGIN role fixtures only when they did not already exist, and removes only the randomly named test database. The test harness refuses external URLs and non-test database names. It does not run Docker and never targets Supabase or the development database. The default CI Testcontainers suite remains available separately.

Local results alone cannot establish hosted network access, real certificate trust, pooler authentication or dashboard API settings. Run a hosted health/auth/tenant-isolation smoke test with controlled accounts after connecting; do not represent the local simulation as a verified Supabase deployment.

## Current hosted connection verification

On 12 November 2026, the supplied credentials successfully authenticated through the Session pooler with verified client TLS 1.3. The bootstrap and all three Flyway migrations completed in `teamflow`. Read-only checks confirmed 15 application/migration tables and denied schema/user-table access for `anon`, `authenticated` and `service_role`. The native frontend and backend now use this hosted database; health is UP and an unknown bearer token receives 401. The old native database is preserved and stopped. Restart this setup with `scripts/start-local.ps1 -Database Supabase -SkipBuild`.

No demo users were inserted into the hosted application database. Dashboard exposed-schema settings were not inspected; keep `teamflow` outside the Data API. Application hosting is still local, and full production readiness is not implied by successful database connection.
