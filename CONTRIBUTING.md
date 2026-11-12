# Contributing

Work on a topic branch from `Test`. Read ARCHITECTURE.md and PROGRESS.md before changing behavior. Keep tenant and parent identifiers explicit in resource queries. Add a PostgreSQL integration assertion for every new authorization path; cover both permitted and denied actions. Use validated request DTOs and response projections, never JPA entities.

Add Flyway migrations with new version numbers; do not edit migrations already deployed to a persistent database. Keep credentials in ignored environment files. Never commit tokens, email contents, database dumps or test traces containing personal production information.

Before review run backend `./mvnw verify`, frontend `npm run lint`, `npm run typecheck`, `npm run test:coverage`, `npm run build`, and the isolated Playwright stack documented in README.md. Docker must be running for Testcontainers. Update documentation and PROGRESS.md with observed results and limitations.

Production account deletion, archival recovery, invitation inboxes and multi-instance rate limiting require explicit design and tests before release. Do not weaken permissions to make a UI action succeed.
