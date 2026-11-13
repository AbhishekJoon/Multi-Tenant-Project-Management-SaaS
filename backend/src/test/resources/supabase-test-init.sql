-- Disposable Testcontainers fixture, never a production migration.
CREATE ROLE anon NOLOGIN;
CREATE ROLE authenticated NOLOGIN;
CREATE ROLE service_role NOLOGIN;
CREATE SCHEMA extensions;
CREATE EXTENSION pg_trgm WITH SCHEMA extensions;
CREATE SCHEMA teamflow;
-- Deliberately simulate accidental API grants. The V3 migration must remove them.
GRANT USAGE ON SCHEMA teamflow TO PUBLIC, anon, authenticated, service_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA teamflow GRANT ALL ON TABLES TO anon, authenticated, service_role;
