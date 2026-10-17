-- SUPERSEDED: src/main/resources/db/migration/V1__baseline.sql now does all of this when ReadyRoom starts
-- (Flyway). Kept for reference and for the upgrade tests; do not run it. See db/README.md.
-- =====================================================================================================
-- 002: the audit trail (ens-audit/1) - indexes for the admin filters, and append-only enforcement.
--
-- Run against an existing ReadyRoom PostgreSQL database, after a version with the audit trail has started
-- at least once (Hibernate creates the audit tables; this script only adds to them):
--
--     psql "postgresql://USER:PASSWORD@HOST:5432/DB" -v ON_ERROR_STOP=1 -f db/migrations/002_audit_log.sql
--
-- Safe to run more than once. Indexes are built with CREATE INDEX CONCURRENTLY, so do NOT run this file
-- inside a transaction (no "psql -1" / --single-transaction).
--
-- What it does
--   1. Adds the indexes the audit log page filters on (Hibernate creates the same ones for a new table;
--      this covers a table created otherwise).
--   2. Makes audit_events append-only in the database itself: UPDATE is refused for everyone, so a stored
--      event can only be added, or deleted by retention pruning. Tampering would also be caught by the
--      hash chain (Verify integrity on the audit page); this stops the casual case outright.
-- =====================================================================================================

\set ON_ERROR_STOP on

-- 0. The tables must already exist. -----------------------------------------------------------------
DO $$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(t, ', ') INTO missing
    FROM unnest(ARRAY['audit_events', 'audit_chain_head', 'audit_output_state', 'audit_api_tokens']) AS t
    WHERE to_regclass('public.' || t) IS NULL;

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'Tables not found: %. Start the application once so Hibernate creates the schema, then run this script again.', missing;
    END IF;
END $$;

-- 1. Indexes. ---------------------------------------------------------------------------------------
-- An index left invalid by an interrupted CONCURRENTLY build is dropped so it is built again.
DO $$
DECLARE
    broken record;
BEGIN
    FOR broken IN
        SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
        WHERE NOT i.indisvalid AND (c.relname LIKE 'ix_audit_%' OR c.relname LIKE 'ux_audit_%')
    LOOP
        EXECUTE format('DROP INDEX IF EXISTS %I', broken.relname);
    END LOOP;
END $$;

CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS ux_audit_events_event_id ON audit_events (event_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_audit_events_time ON audit_events (event_time);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_audit_events_category ON audit_events (category);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_audit_events_action ON audit_events (action);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_audit_events_outcome ON audit_events (outcome);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_audit_events_severity ON audit_events (severity);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_audit_events_actor_id ON audit_events (actor_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_audit_events_target ON audit_events (target_type, target_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_audit_events_source_ip ON audit_events (source_ip);
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS ux_audit_api_tokens_hash ON audit_api_tokens (token_hash);

-- 2. Append-only. -----------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION audit_events_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only: stored audit events cannot be changed (seq %)', OLD.seq;
END $$;

DROP TRIGGER IF EXISTS audit_events_no_update ON audit_events;
CREATE TRIGGER audit_events_no_update
    BEFORE UPDATE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_append_only();

ANALYZE audit_events;

\echo 'Migration 002 complete.'
