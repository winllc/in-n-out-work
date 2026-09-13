package com.winllc.innoutwork.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Measures the hot queries on a seeded database before and after the migration, and the old per-user
 * lookups against the new batched ones. Not part of the normal build (it seeds over a million rows):
 * <pre>
 * PERF_MEASURE=1 ./gradlew test --tests '*QueryPerformanceMeasurement'
 * </pre>
 * Optional PERF_USERS (default 5000) and PERF_DAYS (default 90) size the data. The report is written to
 * build/reports/perf/query-performance.md.
 * <p>
 * The SQL mirrors what the repositories run: the "old" statements are the derived IgnoreCase queries and
 * unbounded subqueries the code used before, the "new" ones the lower() and windowed rewrites.
 */
@EnabledIfEnvironmentVariable(named = "PERF_MEASURE", matches = "1")
class QueryPerformanceMeasurement extends PostgresTestSupport {

    private static final String DB = "perf";
    private static final int RUNS = 5;
    private static final int TABLE_USERS = 200;
    /** Statements slower than this are stopped and reported as exceeding it. */
    private static final int TIMEOUT_SECONDS = 60;

    private record Query(String name, String oldSql, String newSql) {
    }

    /** A median execution time, or {@code timedOut} when a run passed {@link #TIMEOUT_SECONDS}. */
    private record Measured(double ms, String plan, boolean timedOut) {
        boolean usesIndex() {
            return plan.contains("Index");
        }
    }

    private static String dn(int i) {
        return "cn=User %06d,ou=Users,dc=winllc,dc=com".formatted(i);
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    @Test
    void measure() throws Exception {
        int users = Integer.parseInt(System.getenv().getOrDefault("PERF_USERS", "5000"));
        int days = Integer.parseInt(System.getenv().getOrDefault("PERF_DAYS", "90"));
        StringBuilder report = new StringBuilder();

        // A separate database with the schema Hibernate created, so the shared test database stays small.
        ExecResult created = POSTGRES.execInContainer("sh", "-c",
                "createdb -U %1$s %2$s && pg_dump -s -U %1$s %3$s | psql -q -U %1$s -d %2$s"
                        .formatted(POSTGRES.getUsername(), DB, POSTGRES.getDatabaseName()));
        assertEquals(0, created.getExitCode(), created.getStderr());

        POSTGRES.copyFileToContainer(MountableFile.forHostPath(Path.of("db/perf/seed_volume.sql")), "/tmp/seed.sql");
        long seedStart = System.nanoTime();
        ExecResult seeded = POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(), "-d", DB,
                "-v", "users=" + users, "-v", "days=" + days, "-f", "/tmp/seed.sql");
        assertEquals(0, seeded.getExitCode(), seeded.getStdout() + seeded.getStderr());
        long seedSeconds = (System.nanoTime() - seedStart) / 1_000_000_000L;

        String url = POSTGRES.getJdbcUrl().replaceFirst("/" + POSTGRES.getDatabaseName() + "(\\?|$)", "/" + DB + "$1");
        try (Connection db = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement st = db.createStatement()) {
                st.execute("set statement_timeout = '" + TIMEOUT_SECONDS + "s'");
            }
            LocalDate day;
            long checkInRows;
            try (Statement st = db.createStatement();
                 ResultSet rs = st.executeQuery("select max(\"timestamp\")::date, count(*) from check_in_out_records")) {
                rs.next();
                day = rs.getObject(1, LocalDate.class);
                checkInRows = rs.getLong(2);
            }
            String dayStart = quote(day + " 00:00:00");
            String dayEnd = quote(day + " 23:59:59.999999");
            String lookback = quote(day.minusDays(89) + " 00:00:00");
            String user = quote(dn(42));
            String tableDns = IntStream.rangeClosed(1, TABLE_USERS).mapToObj(i -> quote(dn(i)))
                    .collect(Collectors.joining(","));

            List<Query> queries = List.of(
                    new Query("One user's check-ins for a day",
                            "select * from check_in_out_records where upper(dn) = upper(" + user + ") and \"timestamp\" between " + dayStart + " and " + dayEnd + " order by \"timestamp\" desc",
                            "select * from check_in_out_records where lower(dn) = lower(" + user + ") and \"timestamp\" between " + dayStart + " and " + dayEnd + " order by \"timestamp\" desc"),
                    new Query("User record by DN",
                            "select * from user_records where upper(dn) = upper(" + user + ")",
                            "select * from user_records where lower(dn) = lower(" + user + ")"),
                    new Query("One user's statuses for a day",
                            "select * from user_event_records where upper(dn) = upper(" + user + ") and date = " + quote(day.toString()),
                            "select * from user_event_records where lower(dn) = lower(" + user + ") and date = " + quote(day.toString())),
                    new Query(TABLE_USERS + " users' check-ins for a day (one query)",
                            null,
                            "select * from check_in_out_records where lower(dn) in (" + tableDns + ") and \"timestamp\" >= " + dayStart + " and \"timestamp\" <= " + dayEnd),
                    new Query("Users whose last event today is a lock",
                            "select r.* from check_in_out_records r where r.\"timestamp\" = (select max(r2.\"timestamp\") from check_in_out_records r2 where r2.dn = r.dn) and r.action = 'LOCK' and r.\"timestamp\" >= " + dayStart + " and r.\"timestamp\" <= " + dayEnd,
                            "select r.* from check_in_out_records r where r.action = 'LOCK' and r.\"timestamp\" >= " + dayStart + " and r.\"timestamp\" <= " + dayEnd + " and r.\"timestamp\" = (select max(r2.\"timestamp\") from check_in_out_records r2 where r2.dn = r.dn and r2.\"timestamp\" >= " + dayStart + " and r2.\"timestamp\" <= " + dayEnd + ")"),
                    new Query("Last event per user (agent coverage)",
                            "select lower(dn), max(\"timestamp\") from check_in_out_records where dn is not null and \"timestamp\" <= " + dayEnd + " group by lower(dn)",
                            "select lower(dn), max(\"timestamp\") from check_in_out_records where dn is not null and \"timestamp\" >= " + lookback + " and \"timestamp\" <= " + dayEnd + " group by lower(dn)"),
                    new Query("Everyone's statuses for a day",
                            "select * from user_event_records where date = " + quote(day.toString()),
                            "select * from user_event_records where date = " + quote(day.toString())),
                    new Query("Absence notifications already sent today",
                            "select * from notification_records where upper(about_user_dn) = upper(" + user + ") and notification_date between " + dayStart + " and " + dayEnd,
                            "select distinct about_user_dn from notification_records where about_user_dn is not null and notification_date >= " + dayStart + " and notification_date <= " + dayEnd));

            List<Measured> oldBefore = new ArrayList<>();
            List<Measured> newBefore = new ArrayList<>();
            for (Query q : queries) {
                oldBefore.add(q.oldSql() == null ? null : explain(db, q.oldSql()));
                newBefore.add(explain(db, q.newSql()));
            }

            // Group table, old code on the old schema: three lookups per user.
            double perUserOld = timeRoundTrips(db, TABLE_USERS, i -> List.of(
                    "select * from check_in_out_records where upper(dn) = upper(?) and \"timestamp\" between " + dayStart + "::timestamptz and " + dayEnd + "::timestamptz order by \"timestamp\" desc",
                    "select * from user_records where upper(dn) = upper(?)",
                    "select * from user_event_records where upper(dn) = upper(?) and date = " + quote(day.toString()) + "::date"));

            ExecResult migrated = runMigration(DB);
            assertEquals(0, migrated.getExitCode(), migrated.getStdout() + migrated.getStderr());

            List<Measured> newAfter = new ArrayList<>();
            for (Query q : queries) {
                newAfter.add(explain(db, q.newSql()));
            }

            // Group table, new code on the migrated schema: three queries for everyone.
            double batchNew = timeRoundTrips(db, 1, i -> List.of(
                    queries.get(3).newSql(),
                    "select * from user_records where lower(dn) in (" + tableDns + ")",
                    "select * from user_event_records where lower(dn) in (" + tableDns + ") and date >= " + quote(day.toString()) + " and date <= " + quote(day.toString())));

            report.append("# Query performance\n\n")
                    .append("PostgreSQL 16 (container), %d users, %d days, %,d check-in rows, seeded in %ds. ".formatted(users, days, checkInRows, seedSeconds))
                    .append("Execution time is the median of %d runs of EXPLAIN ANALYZE, after one warm-up.\n\n".formatted(RUNS))
                    .append("| Query | Old query, no indexes | New query, no indexes | New query, migrated |\n")
                    .append("|---|---:|---:|---:|\n");
            for (int i = 0; i < queries.size(); i++) {
                report.append("| %s | %s | %s | %s |\n".formatted(queries.get(i).name(),
                        cell(oldBefore.get(i)), cell(newBefore.get(i)), cell(newAfter.get(i))));
            }
            report.append("\n## A group table of %d users\n\n".formatted(TABLE_USERS))
                    .append("| | Statements | Total time |\n|---|---:|---:|\n")
                    .append("| Before: 3 lookups per user, old schema | %d | %,.0f ms |\n".formatted(3 * TABLE_USERS, perUserOld))
                    .append("| After: batched, migrated | 3 | %,.1f ms |\n".formatted(batchNew))
                    .append("\n## Plans after the migration\n\n");
            for (int i = 0; i < queries.size(); i++) {
                report.append("### ").append(queries.get(i).name()).append("\n\n```\n")
                        .append(newAfter.get(i).plan()).append("```\n\n");
            }
        }

        Path out = Path.of("build/reports/perf/query-performance.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.println(report);
    }

    private static String cell(Measured m) {
        if (m == null) {
            return "(was one query per user)";
        }
        if (m.timedOut()) {
            return "> %d s (stopped)".formatted(TIMEOUT_SECONDS);
        }
        return "%s ms%s".formatted(m.ms() < 10 ? "%.2f".formatted(m.ms()) : "%,.0f".formatted(m.ms()),
                m.usesIndex() ? " (index)" : "");
    }

    private static final Pattern EXECUTION_TIME = Pattern.compile("Execution Time: ([0-9.]+) ms");

    private static Measured explain(Connection db, String sql) throws Exception {
        double[] times = new double[RUNS];
        String plan = "";
        for (int run = -1; run < RUNS; run++) {
            StringBuilder text = new StringBuilder();
            try (Statement st = db.createStatement();
                 ResultSet rs = st.executeQuery("explain (analyze, buffers) " + sql)) {
                while (rs.next()) {
                    text.append(rs.getString(1)).append('\n');
                }
            } catch (java.sql.SQLException e) {
                if ("57014".equals(e.getSQLState())) { // query_canceled: statement_timeout
                    StringBuilder estimate = new StringBuilder();
                    try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery("explain " + sql)) {
                        while (rs.next()) {
                            estimate.append(rs.getString(1)).append('\n');
                        }
                    }
                    return new Measured(Double.NaN, estimate.toString(), true);
                }
                throw e;
            }
            Matcher m = EXECUTION_TIME.matcher(text);
            if (!m.find()) {
                throw new IllegalStateException("no execution time in:\n" + text);
            }
            if (run >= 0) {
                times[run] = Double.parseDouble(m.group(1));
                plan = text.toString();
            }
        }
        Arrays.sort(times);
        return new Measured(times[RUNS / 2], plan, false);
    }

    /** Wall time of running the statements once for each of {@code users} users (parameter: that user's DN). */
    private static double timeRoundTrips(Connection db, int users, java.util.function.IntFunction<List<String>> statements)
            throws Exception {
        List<String> sqls = statements.apply(1);
        List<PreparedStatement> prepared = new ArrayList<>();
        try {
            for (String sql : sqls) {
                prepared.add(db.prepareStatement(sql));
            }
            // Warm up.
            runAll(prepared, 1);
            long start = System.nanoTime();
            for (int i = 1; i <= users; i++) {
                runAll(prepared, i);
            }
            return (System.nanoTime() - start) / 1_000_000.0;
        } finally {
            for (PreparedStatement ps : prepared) {
                ps.close();
            }
        }
    }

    private static void runAll(List<PreparedStatement> prepared, int user) throws Exception {
        for (PreparedStatement ps : prepared) {
            if (ps.getParameterMetaData().getParameterCount() > 0) {
                ps.setString(1, dn(user));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // read every row, as the application does
                }
            }
        }
    }

    static {
        Locale.setDefault(Locale.US);
    }
}
