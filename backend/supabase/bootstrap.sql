-- Run once in your project's Supabase SQL Editor before starting TeamFlow.
-- This does not delete or move existing public/auth/storage data.
BEGIN;
CREATE SCHEMA IF NOT EXISTS teamflow;
CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA extensions;
DO $$
DECLARE api_role text;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace
        WHERE e.extname='pg_trgm' AND n.nspname IN ('extensions','teamflow')
    ) THEN
        RAISE EXCEPTION 'pg_trgm must be in extensions or teamflow. Review its current installation before relocating it; no automatic relocation is performed.';
    END IF;
    FOREACH api_role IN ARRAY ARRAY['anon', 'authenticated', 'service_role'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = api_role) THEN
            EXECUTE format('REVOKE ALL ON SCHEMA teamflow FROM %I', api_role);
            EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA teamflow REVOKE ALL ON TABLES FROM %I', api_role);
            EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA teamflow REVOKE ALL ON SEQUENCES FROM %I', api_role);
        END IF;
    END LOOP;
END $$;
REVOKE ALL ON SCHEMA teamflow FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA teamflow REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA teamflow REVOKE ALL ON SEQUENCES FROM PUBLIC;
COMMIT;
