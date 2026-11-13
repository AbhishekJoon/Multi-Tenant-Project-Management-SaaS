-- Preserve application user IDs and all tenant foreign keys when adopting Supabase Auth.
ALTER TABLE users ADD COLUMN supabase_subject uuid UNIQUE;
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE users ADD CONSTRAINT users_auth_identity_check
    CHECK (password_hash IS NOT NULL OR supabase_subject IS NOT NULL);
