-- Postgres-only (PL/pgSQL DO block, not supported by H2 - excluded from the test profile's
-- schema-locations for that reason). Migrates an existing "users" table from the old
-- username-based schema, if present; no-ops on a fresh install where the table doesn't exist
-- yet or has already been migrated.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'username') THEN
        ALTER TABLE users RENAME COLUMN username TO name;
        ALTER TABLE users DROP CONSTRAINT IF EXISTS users_username_key;
    END IF;
END $$;
