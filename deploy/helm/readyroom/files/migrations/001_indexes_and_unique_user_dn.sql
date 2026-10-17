-- =====================================================================================================
-- 001: indexes for the application's queries, and one user record per DN.
--
-- Run against an existing In-N-Out-Work PostgreSQL database, after the application has started at least
-- once (Hibernate creates the tables; this script only adds to them):
--
--     psql "postgresql://USER:PASSWORD@HOST:5432/DB" -v ON_ERROR_STOP=1 -f db/migrations/001_indexes_and_unique_user_dn.sql
--
-- Safe to run more than once: every step checks whether it is still needed. Indexes are built with
-- CREATE INDEX CONCURRENTLY, so the application can stay up and keep writing while it runs; for that
-- reason do NOT run this file inside a transaction (no "psql -1" / --single-transaction).
--
-- What it does
--   1. Merges user_records rows whose DNs differ only by letter case, which concurrent first sign-ins
--      could create. Lookups by DN expect one row and fail for a user who has two.
--   2. Enforces one user record per DN, ignoring case, so it cannot happen again.
--   3. Adds indexes matching how the application looks rows up: by lower-cased DN, by time range and
--      by session or notification id.
-- =====================================================================================================

\set ON_ERROR_STOP on

-- 0. The tables must already exist. -----------------------------------------------------------------
DO $$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(t, ', ') INTO missing
    FROM unnest(ARRAY['user_records', 'permission_records', 'check_in_out_records', 'user_event_records',
                      'notification_records', 'group_records', 'global_calendar_records']) AS t
    WHERE to_regclass('public.' || t) IS NULL;

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'Tables not found: %. Start the application once so Hibernate creates the schema, then run this script again.', missing;
    END IF;
END $$;


-- 1. Merge user records that differ only by DN case. --------------------------------------------------
-- The oldest row (lowest id) is kept. Its empty fields are filled from the duplicates, favourites and
-- alternate managers are combined, the most privileged role wins, permissions move across without
-- repeating a group, and the duplicates are deleted. A no-op when there are no duplicates.
BEGIN;

-- Hold off concurrent inserts and updates of user records while merging.
LOCK TABLE user_records IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE user_record_duplicates ON COMMIT DROP AS
SELECT id, keep_id
FROM (SELECT id, min(id) OVER (PARTITION BY lower(dn)) AS keep_id FROM user_records) ranked
WHERE id <> keep_id;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM user_record_duplicates) THEN
        RAISE NOTICE 'Merging % duplicate user record(s) into % user(s)',
            (SELECT count(*) FROM user_record_duplicates),
            (SELECT count(DISTINCT keep_id) FROM user_record_duplicates);
    ELSE
        RAISE NOTICE 'No duplicate user records';
    END IF;
END $$;

WITH grouped AS (
    SELECT g.keep_id,
           -- every row of the group, kept row included, oldest first
           array_agg(u.id ORDER BY u.id) AS ids
    FROM (SELECT DISTINCT keep_id FROM user_record_duplicates) g
    JOIN user_records u ON lower(u.dn) = (SELECT lower(dn) FROM user_records WHERE id = g.keep_id)
    GROUP BY g.keep_id
),
merged AS (
    SELECT g.keep_id,
           (array_agg(u.notes                 ORDER BY u.id) FILTER (WHERE u.notes                 IS NOT NULL))[1] AS notes,
           (array_agg(u.organization          ORDER BY u.id) FILTER (WHERE u.organization          IS NOT NULL))[1] AS organization,
           (array_agg(u.employee_type         ORDER BY u.id) FILTER (WHERE u.employee_type         IS NOT NULL))[1] AS employee_type,
           (array_agg(u.location              ORDER BY u.id) FILTER (WHERE u.location              IS NOT NULL))[1] AS location,
           (array_agg(u.branch                ORDER BY u.id) FILTER (WHERE u.branch                IS NOT NULL))[1] AS branch,
           (array_agg(u.duty_sub_organization ORDER BY u.id) FILTER (WHERE u.duty_sub_organization IS NOT NULL))[1] AS duty_sub_organization,
           (array_agg(u.phone_number          ORDER BY u.id) FILTER (WHERE u.phone_number          IS NOT NULL))[1] AS phone_number,
           (array_agg(u.email                 ORDER BY u.id) FILTER (WHERE u.email                 IS NOT NULL))[1] AS email,
           (array_agg(u.average_login_time    ORDER BY u.id) FILTER (WHERE u.average_login_time    IS NOT NULL))[1] AS average_login_time,
           (array_agg(u.chosen_login_time     ORDER BY u.id) FILTER (WHERE u.chosen_login_time     IS NOT NULL))[1] AS chosen_login_time,
           (array_agg(u.user_role ORDER BY CASE u.user_role WHEN 'ADMIN' THEN 1 WHEN 'MANAGER' THEN 2 WHEN 'USER' THEN 3 ELSE 4 END, u.id)
                FILTER (WHERE u.user_role IS NOT NULL))[1] AS user_role,
           nullif(array_to_string(ARRAY(
               SELECT DISTINCT v FROM user_records x, unnest(string_to_array(x.favorite_groups, ';')) AS v
               WHERE x.id = ANY (g.ids) AND v <> '' ORDER BY v), ';'), '') AS favorite_groups,
           nullif(array_to_string(ARRAY(
               SELECT DISTINCT v FROM user_records x, unnest(string_to_array(x.alternate_managers, ';')) AS v
               WHERE x.id = ANY (g.ids) AND v <> '' ORDER BY v), ';'), '') AS alternate_managers
    FROM grouped g
    JOIN user_records u ON u.id = ANY (g.ids)
    GROUP BY g.keep_id, g.ids
)
UPDATE user_records k
SET notes                 = m.notes,
    organization          = m.organization,
    employee_type         = m.employee_type,
    location              = m.location,
    branch                = m.branch,
    duty_sub_organization = m.duty_sub_organization,
    phone_number          = m.phone_number,
    email                 = m.email,
    average_login_time    = m.average_login_time,
    chosen_login_time     = m.chosen_login_time,
    user_role             = m.user_role,
    favorite_groups       = m.favorite_groups,
    alternate_managers    = m.alternate_managers
FROM merged m
WHERE k.id = m.keep_id;

-- Permissions: drop a duplicate's grant the kept user already holds, then move the rest across.
DELETE FROM permission_records p
USING user_record_duplicates d, permission_records kept
WHERE p.user_id = d.id
  AND kept.user_id = d.keep_id
  AND lower(kept.group_dn) = lower(p.group_dn);

UPDATE permission_records p
SET user_id = d.keep_id
FROM user_record_duplicates d
WHERE p.user_id = d.id;

-- Two duplicates may both have held the same grant; keep one.
DELETE FROM permission_records p
USING permission_records q
WHERE p.user_id = q.user_id
  AND lower(p.group_dn) = lower(q.group_dn)
  AND p.id > q.id
  AND p.user_id IN (SELECT keep_id FROM user_record_duplicates);

DELETE FROM user_records u
USING user_record_duplicates d
WHERE u.id = d.id;

COMMIT;


-- 2 & 3. Indexes. -----------------------------------------------------------------------------------
-- A CONCURRENTLY build that fails part-way leaves an INVALID index behind, which IF NOT EXISTS would
-- then skip for ever. Drop any such leftovers of ours so this run rebuilds them.
DO $$
DECLARE
    leftover text;
BEGIN
    FOR leftover IN
        SELECT c.relname
        FROM pg_index i
        JOIN pg_class c ON c.oid = i.indexrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public'
          AND NOT i.indisvalid
          AND c.relname IN ('ux_user_records_dn_lower', 'ix_check_in_out_dn_lower_ts', 'ix_check_in_out_dn_ts',
                            'ix_check_in_out_ts', 'ix_check_in_out_session_id', 'ix_user_events_dn_lower_date',
                            'ix_user_events_date', 'ix_notifications_for_dn_lower', 'ix_notifications_about_dn_lower_date',
                            'ix_notifications_uuid', 'ix_permission_records_user_id', 'ix_group_records_group_dn_lower',
                            'ix_global_calendar_date')
    LOOP
        RAISE NOTICE 'Dropping invalid index % left by an earlier failed run', leftover;
        EXECUTE format('DROP INDEX public.%I', leftover);
    END LOOP;
END $$;

-- One user record per DN, ignoring case. Fails if a duplicate was inserted after step 1 committed; just
-- run the script again.
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS ux_user_records_dn_lower
    ON user_records (lower(dn));

-- Check-in/out records: a user's records in a time window (status, history, reports) ...
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_check_in_out_dn_lower_ts
    ON check_in_out_records (lower(dn), "timestamp");
-- ... each DN's latest record (correlated MAX(timestamp) lookups compare the DN exactly) ...
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_check_in_out_dn_ts
    ON check_in_out_records (dn, "timestamp");
-- ... everyone's records in a window (dashboard, metrics) ...
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_check_in_out_ts
    ON check_in_out_records ("timestamp");
-- ... and the check-out looked up by the session its check-in started.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_check_in_out_session_id
    ON check_in_out_records (session_id);

-- Statuses: a user's day or range, and everyone's statuses for a day.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_user_events_dn_lower_date
    ON user_event_records (lower(dn), date);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_user_events_date
    ON user_event_records (date);

-- Notifications: a manager's inbox, alerts about a user in a window, and the copies of one alert.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_notifications_for_dn_lower
    ON notification_records (lower(for_user_dn));
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_notifications_about_dn_lower_date
    ON notification_records (lower(about_user_dn), notification_date);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_notifications_uuid
    ON notification_records (notification_uuid);

-- Smaller tables, looked up on most requests that touch them.
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_permission_records_user_id
    ON permission_records (user_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_group_records_group_dn_lower
    ON group_records (lower(group_dn));
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_global_calendar_date
    ON global_calendar_records (date);

-- Give the planner fresh statistics for the new indexes.
ANALYZE user_records;
ANALYZE check_in_out_records;
ANALYZE user_event_records;
ANALYZE notification_records;

\echo 'Migration 001 complete.'
