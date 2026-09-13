-- Fills an EMPTY database with a realistic volume of attendance data, for measuring query performance.
-- For a throwaway database only: it refuses to run when user_records already has rows.
--
--   psql "postgresql://USER:PASSWORD@HOST:5432/DB" -v users=5000 -v days=90 -f db/perf/seed_volume.sql
--
-- Start the application against the database once first, so Hibernate creates the tables.
--
-- What it writes, for `users` users over the last `days` days (weekdays only):
--   user_records          one per user
--   check_in_out_records  check in, lock, unlock, check out on 19 of every 20 working days; one agent in
--                         ten reports its DN in upper case, as some do
--   user_event_records    a status (TDY, telework, leave, out of office) on half of the other days
--   notification_records  an absence notification to the user's manager on the rest
-- With the defaults that is about 1.2 million check-in rows.

\set ON_ERROR_STOP on
\if :{?users}
\else
    \set users 5000
\endif
\if :{?days}
\else
    \set days 90
\endif

SELECT count(*) > 0 AS has_users FROM user_records \gset
\if :has_users
    \echo 'user_records is not empty. This script is for an empty, throwaway database; nothing was written.'
    DO $$ BEGIN RAISE EXCEPTION 'Refusing to seed a database that already has users'; END $$;
\endif

\echo 'Seeding' :users 'users over' :days 'days...'

BEGIN;

CREATE TEMP TABLE seed_users ON COMMIT DROP AS
SELECT i, format('cn=User %s,ou=Users,dc=winllc,dc=com', lpad(i::text, 6, '0')) AS dn
FROM generate_series(1, :users) AS i;

CREATE TEMP TABLE seed_days ON COMMIT DROP AS
SELECT day::date AS day, extract(doy FROM day)::int AS doy
FROM generate_series(current_date - :days, current_date - 1, interval '1 day') AS day
WHERE extract(isodow FROM day) < 6;

INSERT INTO user_records (dn, user_role, organization, employee_type, duty_sub_organization)
SELECT dn, 'USER', 'WinLLC', CASE WHEN i % 5 = 0 THEN 'CON' ELSE 'FT' END,
       format('RYS%sB', lpad((i % 40)::text, 2, '0'))
FROM seed_users;

INSERT INTO check_in_out_records (dn, "timestamp", action, session_id, forced)
SELECT CASE WHEN u.i % 10 = 0 THEN upper(u.dn) ELSE u.dn END,
       (d.day + e.at + make_interval(mins => (u.i * 7 + d.doy) % 60))::timestamptz,
       e.action,
       md5(u.i::text || d.day::text),
       false
FROM seed_users u
CROSS JOIN seed_days d
CROSS JOIN (VALUES ('CHECK_IN', time '07:30'), ('LOCK', time '12:00'),
                   ('UNLOCK', time '12:40'), ('CHECK_OUT', time '16:30')) AS e(action, at)
WHERE (u.i + d.doy) % 20 <> 0;

INSERT INTO user_event_records (dn, date, status)
SELECT u.dn, d.day, (ARRAY['TDY', 'WORK_FROM_HOME', 'SCHEDULED_LEAVE', 'OUT_OF_OFFICE'])[1 + (u.i / 2) % 4]
FROM seed_users u
CROSS JOIN seed_days d
WHERE (u.i + d.doy) % 20 = 0 AND u.i % 2 = 0;

INSERT INTO notification_records (notification_uuid, for_user_dn, about_user_dn, notification_date, type, ignore)
SELECT md5('n' || u.i::text || d.day::text),
       format('cn=Manager %s,ou=Users,dc=winllc,dc=com', u.i % 100),
       u.dn,
       (d.day + time '10:00')::timestamptz,
       'ABSENT',
       false
FROM seed_users u
CROSS JOIN seed_days d
WHERE (u.i + d.doy) % 20 = 0 AND u.i % 2 = 1;

COMMIT;

ANALYZE user_records;
ANALYZE check_in_out_records;
ANALYZE user_event_records;
ANALYZE notification_records;

SELECT (SELECT count(*) FROM user_records) AS users,
       (SELECT count(*) FROM check_in_out_records) AS check_in_rows,
       (SELECT count(*) FROM user_event_records) AS status_rows,
       (SELECT count(*) FROM notification_records) AS notification_rows;
