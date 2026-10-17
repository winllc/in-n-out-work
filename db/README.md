# Database migrations

ReadyRoom's schema is owned by [Flyway](https://documentation.red-gate.com/flyway). The migrations are
SQL files in [`src/main/resources/db/migration/`](../src/main/resources/db/migration/), run by Spring
Boot's Flyway integration when the application starts, before anything else touches the database. Hibernate
only validates the result (`spring.jpa.hibernate.ddl-auto: validate`), so an entity whose table or column no
migration creates stops the application at startup with the missing name.

| Path | What |
|---|---|
| `src/main/resources/db/migration/V<n>__<description>.sql` | The migrations. Packaged in the jar; nothing else ships them. |
| `db/legacy/` | The psql scripts used before Flyway (`001`, `002`). Superseded by `V1__baseline.sql`; kept for reference and for the upgrade tests. Never run them on a new install. |
| `db/perf/seed_volume.sql` | Volume data for `QueryPerformanceMeasurement`. |
| `src/test/resources/db/legacy-hibernate-schema.sql` | Frozen fixture: what Hibernate created before Flyway. Never regenerate it. |

## Adding a migration

1. **Name it** `V<next>__<what_it_does>.sql`: the next integer after the highest one in
   `src/main/resources/db/migration/` (`V2__`, `V3__`, ...), two underscores, then lower-case words joined by
   underscores, for example `V2__approval_requests.sql`. No gaps are needed, but never reuse or skip back.
2. **Write plain PostgreSQL** (the supported versions are 13 and later; CI and the tests run 16). Each file
   runs in one transaction, so it applies completely or not at all:
   - no `CREATE INDEX CONCURRENTLY` (not allowed in a transaction); a plain `CREATE INDEX` is fine, since
     migrations run while the application is starting and serving nothing;
   - no psql meta-commands (`\set`, `\echo`);
   - a `${...}` in the SQL is a Flyway placeholder; avoid it, or escape it.
3. **Change the entity in the same commit.** A new entity column needs its column in a migration, with the
   type Hibernate expects (look at an existing one: `varchar(255)` for a plain `String`,
   `timestamp(6) with time zone` for `Instant`/`ZonedDateTime`, `time(0) without time zone` for
   `LocalTime`, `text` for `@Enumerated(STRING)` with `columnDefinition = "text"`).
4. **Enum values.** The enumerated columns have CHECK constraints listing their values
   (`check_in_out_records_action_check`, `user_records_user_role_check`, `user_event_records_status_check`,
   `notification_records_type_check`, `notification_records_status_response_check`). Adding a value to one
   of those enums needs a migration that drops the check and adds it again with the new list, as V1 does.
   A new enumerated column should get a check the same way.
5. **Never edit a released migration.** Flyway stores each file's checksum and refuses to start if one
   changes. Fix a mistake with a new migration.
6. **Data changes** go in migrations too (SQL `UPDATE`s), written so they are correct on a database with
   any amount of data, including none.
7. Only V1 had to cope with databases created before Flyway. From V2 on, a migration runs exactly once on a
   database whose history Flyway knows, so it does not need `IF NOT EXISTS` guards (they do no harm).

## Testing

- `FlywayMigrationTest` (Testcontainers PostgreSQL; needs Docker) runs the migrations as `application.yml`
  configures Flyway, on an empty database and on databases the pre-Flyway releases left, and checks they end
  with the same schema. A new migration is covered by it automatically; add a test there when the migration
  moves or transforms data, or must behave differently on existing rows.
- Every test that extends `migration/PostgresTestSupport` runs on a database Flyway built, with Hibernate
  validating it, so a missing or mistyped column fails those tests too.
- The H2 slices (`@DataJpaTest` and `@SpringBootTest` classes with `jdbc:h2:` URLs) build their schema
  from the entities (`ddl-auto=create-drop`, `spring.flyway.enabled=false`): the migrations use
  PostgreSQL-only SQL that H2 cannot run. Keep that pair of properties on any new H2 test.

```bash
./gradlew test --tests '*FlywayMigrationTest' --tests '*PostgresTest' --tests '*ConcurrentWritesTest'
```

## Settings

| Setting | Default | Meaning |
|---|---|---|
| `spring.flyway.baseline-on-migrate` | `true` | A schema with tables but no `flyway_schema_history` (created before Flyway) is recorded at the baseline version instead of refused |
| `spring.flyway.baseline-version` | `0` | So V1 still runs on such a schema |
| `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` | the application's own account | A separate account that owns and migrates the schema; see "Database migrations" in the README for the grants |
