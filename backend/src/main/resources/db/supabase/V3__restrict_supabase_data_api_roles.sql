-- Loaded only by the supabase profile. Do not move into the shared migration location.
DO $$
DECLARE api_role text;
BEGIN
    IF current_schema() <> 'teamflow' THEN
        RAISE EXCEPTION 'Supabase migrations must run in the private teamflow schema';
    END IF;
    FOREACH api_role IN ARRAY ARRAY['anon', 'authenticated', 'service_role'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = api_role) THEN
            EXECUTE format('REVOKE ALL ON SCHEMA teamflow FROM %I', api_role);
            EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA teamflow FROM %I', api_role);
            EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA teamflow FROM %I', api_role);
            EXECUTE format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA teamflow FROM %I', api_role);
            EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA teamflow REVOKE ALL ON TABLES FROM %I', api_role);
            EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA teamflow REVOKE ALL ON SEQUENCES FROM %I', api_role);
        END IF;
    END LOOP;
END $$;
REVOKE ALL ON SCHEMA teamflow FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA teamflow FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA teamflow FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA teamflow FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA teamflow REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA teamflow REVOKE ALL ON SEQUENCES FROM PUBLIC;
