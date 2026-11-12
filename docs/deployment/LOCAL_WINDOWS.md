# Local Windows development without Docker

Run Spring Boot with Java and the React Vite development server with Node. PostgreSQL and Mailpit also run as local Windows processes. Docker is not involved in this mode.

This machine's portable dependencies are installed under the ignored `.tools/native` directory:

- PostgreSQL binaries: `postgresql/pgsql/bin`; persistent cluster: `postgres-data`.
- Mailpit executable: `mailpit/mailpit.exe`; persistent local mail database: `mailpit.db`.
- The existing development database was backed up before stopping Docker. On the first native startup, `teamflow-backup.sql` is restored into a new local database. The original Docker volume is retained.

From the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/start-local.ps1
# With the current backend jar already built:
powershell -ExecutionPolicy Bypass -File scripts/start-local.ps1 -SkipBuild
```

Open http://localhost:5173. Backend: http://localhost:8080. Local email inbox: http://localhost:8025. The Vite server proxies API requests to Spring Boot. Services bind to loopback only. Runtime environment variables override Docker hostnames from `.env`; the file's existing database password is reused and never printed.

```powershell
powershell -ExecutionPolicy Bypass -File scripts/stop-local.ps1
```

The stop script verifies saved process IDs, executable paths and creation times before stopping them. It stops only the project-local PostgreSQL cluster and retains data. Logs are in `.tools/native/*.log`. Temporary password initialization files are removed after use. Keep `.tools` and `.env` out of Git; neither is a backup strategy.

On another Windows machine, use Java 21 and Node 24, run `npm ci` in frontend, copy `.env.example` to `.env` and set a unique database password. Download the Windows ZIP from [PostgreSQL's official Windows download page](https://www.postgresql.org/download/windows/) (EDB binary archives) and the Windows executable from [Mailpit's installation instructions](https://mailpit.axllent.org/docs/install/). Extract them to the paths above. No system-wide installation or Windows service is required. PostgreSQL 17 includes the required `pg_trgm` extension. Without an existing backup the backend's Flyway migrations initialize an empty database.

The PostgreSQL/Mailpit downloads are machine-local tooling, not application dependencies shipped to users. Backend integration tests still use Testcontainers and require Docker when you choose to run that test suite later.
