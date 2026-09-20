# ReadyRoom

Attendance and presence tracking for an LDAP-backed organization. Windows
workstations report logon, logoff, screen lock and screen unlock events to a
Spring Boot server, which turns them into per-user daily attendance, group
rollups, an org chart and absence notifications.

- **Server** — Spring Boot 4 / Java 21, Thymeleaf UI, PostgreSQL, LDAP directory
- **Client** — a PowerShell script driven by Windows Task Scheduler
- **Auth** — mutual TLS with X.509 client certificates, falling back to LDAP
  username/password; optionally Windows (Kerberos) sign-in for the client's check-ins

---

## How it fits together

```
Windows workstation                     Server                        Backing services
-------------------                     ------                        ----------------
Task Scheduler                     ┌─────────────────┐               ┌────────────┐
  logon   ──┐                      │  /api/check/*   │──────────────▶│ PostgreSQL │
  lock    ──┼─▶ Update-WorkStatus  │  (REST)         │   records     └────────────┘
  unlock  ──┤     .ps1  ──mTLS────▶│                 │
  logoff  ──┘                      │  /app/*         │──────────────▶┌────────────┐
                                   │  (Thymeleaf UI) │  users/groups │   LDAP     │
                                   └─────────────────┘               └────────────┘
```

The directory is the source of truth for people and org structure; PostgreSQL
stores only what LDAP cannot: check-in/out records, roles, permissions,
notes, favorites and calendar events.

### Authentication

`SecurityConfig` prefers a client certificate and uses the **full subject DN as
the username** (`subjectPrincipalRegex("(.*)")`), which `AppUserDetailsService`
resolves against LDAP. Browsers without a certificate fall back to the branded
form login at `/login`, validated by the LDAP authentication provider.

The Windows client's check-in, lock and unlock calls can also sign in with the
user's Windows logon (Kerberos). This is off by default; see
[Windows authentication](#windows-authentication).

Everything requires authentication except:

| Path | Why |
|---|---|
| `/api/check/out` | Runs at logoff, after the user profile — and their certificate store — is gone |
| `/actuator/**` | Health and metrics |
| `/login`, `/error` | Sign-in and error pages |

That single exception drives the client design: since logoff cannot authenticate,
login caches the server-issued `sessionId`, and logout echoes it back so the
server can recover the DN via `lookupBySessionId()`.

### Roles

`USER`, `MANAGER`, `ADMIN` (`UserRoleEnum`). Access to a group's users is granted
either by role or per-group through `PermissionRecord`, evaluated by
`PermissionEvaluator.groupCheck()` in `@PreAuthorize` expressions.

---

## Running locally

### 1. Backing services

```bash
cd test && docker compose up -d
```

Brings up PostgreSQL, OpenLDAP, [phpLDAPadmin](http://localhost:8081),
[Adminer](http://localhost:8082) and a `step-ca` certificate authority.

| Service | Port |
|---|---|
| PostgreSQL | 5432 |
| LDAP / LDAPS | 1389 / 1636 |
| phpLDAPadmin | 8081 |
| Adminer | 8082 |
| step-ca | 8443 |

### 2. Seed mock data

```bash
cd test && ./seed-mock-data.sh
```

Adds mock users and groups to LDAP plus historical, current and future
check-in/out records. It talks to the containers via `docker exec`, so no local
`psql` or `ldap-utils` needed, and it is safe to re-run.

### 3. Run the app

```bash
./gradlew bootRun
```

Then open <https://localhost:8444>. Requires a JDK 21 on `JAVA_HOME`.

### Everything in containers instead

```bash
cd test && docker compose -f docker-compose.app.yml up --build
```

This builds the app from source and runs it alongside its own PostgreSQL and
OpenLDAP. It reuses the same container names as `docker-compose.yml`, so do not
run both compose files at once.

---

## Product page

`product-site/` is a static page introducing the application, served by
unprivileged nginx in its own container on the same host as the app.

```bash
cd product-site && docker compose up -d --build
```

Browse to `http://<host>/`. Every "Open the app" link goes to `/launch`, which
redirects to the application.

| Variable | Default | Purpose |
|---|---|---|
| `PRODUCT_SITE_PORT` | `80` | Host port for the product page |
| `APP_URL` | `https://$host:8444/` | Where `/launch` redirects. `$host` is whatever hostname the visitor used, so the default reaches the app on the same host. Set a full URL if the app is reached another way. |

The page loads nothing from other origins and runs no scripts, which lets nginx
send a strict Content-Security-Policy; keep new markup free of inline `style`
attributes and `<script>` tags. `/healthz` backs the container health check. The
logo and favicon in `product-site/site/assets/` are copies of the app's, so
update both when the branding changes.

---

## Tests

```bash
./gradlew test
```

JUnit 5 + Mockito, with `@WebMvcTest` slices for the controllers. LDAP code
runs against an in-memory UnboundID directory. Tests of the migration script and
of concurrent writes run against PostgreSQL in a container (Testcontainers), and
are skipped when Docker is not available. Windows sign-in is tested with real
Kerberos tickets from an in-process KDC (Apache Kerby), no domain needed.

> **Note for controller tests:** `@SpringBootApplication` lives in
> `com.winllc.innoutwork.config`, which is not a parent package of the tests. In
> a `@WebMvcTest` the nested `@Configuration` therefore becomes the slice's only
> configuration class, no component scan happens, and **no controllers get
> registered** — every request 404s. The existing controller tests work around
> this by importing the controller explicitly:
>
> ```java
> @WebMvcTest(HomeController.class)
> @Import({HomeController.class, HomeControllerTest.TestSecurityConfig.class})
> ```
>
> Test security configs must also use `subjectPrincipalRegex("(.*)")` to match
> production — controllers parse the principal name as an `LdapDn`, which throws
> on a bare CN.

---

## Windows client

Everything lives in [`powershell/`](powershell/). One script handles all four
events, selected by `-Action`:

```bash
powershell.exe -ExecutionPolicy Bypass -File "C:\Program Files\InOutWorker\Update-WorkStatus.ps1" -Action login
```

| Action | Endpoint | Sign-in | Runs as |
|---|---|---|---|
| `login` | `POST /api/check/in` | certificate or Windows | the user |
| `lock` | `POST /api/check/lock` | certificate or Windows | the user |
| `unlock` | `POST /api/check/unlock` | certificate or Windows | the user |
| `logout` | `POST /api/check/out` | none | SYSTEM |

`Authentication` in the config file (or `-Authentication`) chooses how login,
lock and unlock sign in:

| Value | Signs in with |
|---|---|
| `Certificate` | The user's client certificate. The default, and the only option before Windows sign-in existed. |
| `Windows` | The user's Windows logon. Needs Windows sign-in enabled on the server (see [Windows authentication](#windows-authentication)). |
| `Auto` | Windows first, then the certificate if that fails, for example on a laptop that cannot reach a domain controller. |

Install the script and register all four scheduled tasks from an **elevated**
prompt:

```bash
cd powershell; .\Install-InOutWorker.ps1 -BaseUrl "https://your-server:8444/api/check"
```

Add `-Authentication Windows` or `-Authentication Auto` to write that setting too.

Use `-WhatIf` on either script to see what would happen without changing
anything. The client logs to `%ProgramData%\InOutWorker\logs\`.

Configuration comes from `inoutworker.config.json` next to the script (see
[`inoutworker.config.sample.json`](powershell/inoutworker.config.sample.json)),
overridden by explicit parameters — the script itself never needs editing per
machine.

> Server TLS validation is **on** by default. If your server uses a self-signed
> certificate, pass `-SkipCertificateCheck`, otherwise the tasks will fail.

The superseded `login.ps1`, `logout.ps1` and `updatestatus.ps1` are still
present for reference. `updatestatus.ps1` never worked — it uses `==`, which
parses in PowerShell but throws at runtime.

---

## Configuration

Server settings live under the `application:` prefix in
`src/main/resources/application.yml`, bound to `ApplicationProperties`. The
values that most often need changing per deployment:

| Property | Purpose |
|---|---|
| `user-base-dn` | Base DN for user searches |
| `user-ldap-filter` | Filter identifying user entries; defaults to `(objectclass=inetOrgPerson)`. Used alone to enumerate and count users, and combined into an AND for search, reports and the org chart, so it applies everywhere users are looked up. Parentheses are added if omitted. |
| `groups[].groups-base-dn` | One entry per top-level group tree shown in the UI |
| `super-user-dns` | DNs always treated as `ADMIN` |
| `user-ldap-*-attribute` | Maps LDAP attributes to organization, location, branch, employee type, manager, duty sub-organization |
| `duty-sub-org-groups-base-dn` | Enables the org chart; blank disables it |
| `checkOutAfterMinutes` | Idle time before `MarkInactiveCron` auto-checks-out a session |
| `application-base-url` | Used in notification emails |

Two background jobs run on intervals set by `inactive-cron` and
`send-absent-notification-cron`: one auto-checks-out idle users, the other emails
absence notifications.

### Org chart parsing

The org chart derives hierarchy from a single flat LDAP attribute. By default a
value splits on letter/digit boundaries — `RYS34B` becomes `RYS` → `34` → `B` —
and trees sharing a prefix merge into one. When that heuristic does not fit,
add an `OrgParseRuleRecord` under **Settings** with a regex whose capture groups
define the levels.

---

## Windows authentication

The Windows client can sign in with the user's Windows logon instead of, or as
well as, a client certificate. The server checks the Kerberos ticket Windows
sends, finds the directory user with that account name, and records the
check-in under the same DN a certificate for that person would give. Only
`POST /api/check/in`, `/lock` and `/unlock` accept Windows sign-in. Every other
URL, including logout and the web pages, is unchanged.

**Certificates keep working exactly as before.** With Windows sign-in turned
on, a certificate on those three calls is checked first, in the same way, and a
request it signs in never reaches the Kerberos code. The only other difference
on those calls: a request with neither a certificate nor a ticket gets
`401 Negotiate` (which prompts Windows to send a ticket) instead of a redirect
to the login page. With it turned off (the default), nothing changes.

### 1. Active Directory (needs a domain admin)

1. Create a service account for the app, for example `svc-inout`, with a strong
   password that doesn't expire. Tick **This account supports Kerberos AES 256
   bit encryption**.
2. Register the app's host name on it. Use the name clients put in `BaseUrl`,
   and not an IP address:

   ```bash
   setspn -S HTTP/inout.winllc.com WINLLC\svc-inout
   ```

3. Export a keytab for it (AES only; Java rejects RC4):

   ```bash
   ktpass -out http.keytab -princ HTTP/inout.winllc.com@WINLLC.COM -mapUser WINLLC\svc-inout -pass * -crypto AES256-SHA1 -ptype KRB5_NT_PRINCIPAL
   ```

   `ktpass` sets a new password on the account as it exports the key. Treat
   `http.keytab` like a password: anyone with it can pose as the app. Re-export
   it whenever the account's password changes.

### 2. Server

Add to the application configuration (or the matching `APPLICATION_WINDOWSAUTH_*`
environment variables):

```yaml
application:
  windows-auth:
    enabled: true
    service-principal: HTTP/inout.winllc.com@WINLLC.COM
    keytab-location: /etc/in-n-out/http.keytab
    account-attribute: sAMAccountName   # or userPrincipalName, with strip-realm: false
```

| Setting | Default | Purpose |
|---|---|---|
| `enabled` | `false` | Turns Windows sign-in on for the three check-in calls. |
| `service-principal` | | The app's principal from step 1. The app won't start without it when enabled. |
| `keytab-location` | | The keytab from step 1. The app won't start if it can't read it. |
| `account-attribute` | `sAMAccountName` | Directory attribute holding the Windows account name. |
| `strip-realm` | `true` | Match `jdoe` from `jdoe@WINLLC.COM`. Set `false` to match the whole principal (for `userPrincipalName`). |
| `allowed-realms` | the service principal's realm | Realms whose users may sign in, so a trusted domain's `jdoe` isn't taken for yours. |
| `krb5-config-location` | system `/etc/krb5.conf` | A different krb5.conf. |
| `debug` | `false` | JDK Kerberos detail while loading the keytab. |

The account name must match exactly one user under `user-base-dn` that also
matches `user-ldap-filter`. Otherwise sign-in is refused and the reason is
logged. Checking a ticket needs only the keytab. The server doesn't contact a
domain controller, so a krb5.conf is optional. Mount one if you want to set
realm defaults.

**Container.** The image includes the Kerberos tools (`krb5-workstation`). Mount
the keytab read-only, never bake it into the image:

```bash
docker run ... -v /secure/http.keytab:/etc/in-n-out/http.keytab:ro -e APPLICATION_WINDOWSAUTH_ENABLED=true -e APPLICATION_WINDOWSAUTH_SERVICEPRINCIPAL=HTTP/inout.winllc.com@WINLLC.COM -e APPLICATION_WINDOWSAUTH_KEYTABLOCATION=/etc/in-n-out/http.keytab in-n-out-work
```

The container user must be able to read the file. To check the keytab inside the
container:

```bash
docker exec innout-app klist -kte /etc/in-n-out/http.keytab
```

`test/docker-compose.app.yml` has the same settings commented out.

The server's clock must be within 5 minutes of the domain controllers'.

### 3. Clients

Set `"Authentication": "Auto"` (or `"Windows"`) in `inoutworker.config.json`, and
make sure `BaseUrl` uses the host name registered in step 1. PCs must be
domain-joined and users signed in with domain accounts.

### Troubleshooting

The server logs why each refused sign-in failed, without the ticket itself.

| Log message | Cause |
|---|---|
| `offered NTLM, which is not supported` | Windows had no Kerberos ticket for the URL: `BaseUrl` uses an IP address or a name that isn't the registered one, or the PC can't reach a domain controller. |
| `Kerberos validation not successful (Checksum failed)` | The keytab doesn't match the account's current key: re-export it, or the principal name differs from the one clients ask for. |
| `Clock skew too great` | Server and domain controller clocks differ by more than 5 minutes. |
| `no single directory user has sAMAccountName=...` | No directory user has that account name, or more than one does. Check `account-attribute`, `user-base-dn` and `user-ldap-filter`. |
| `realm not allowed` | The user is from another domain. Add it to `allowed-realms` if intended. |

On the client, `The server redirected instead of accepting Windows sign-in`
means `application.windows-auth.enabled` is off on that server.

---

## Database migrations

Hibernate creates tables and columns (`ddl-auto: update`) but not indexes or
constraints. Those come from the scripts in [`db/migrations/`](db/migrations/),
which you run yourself in each environment with `psql`.

**`001_indexes_and_unique_user_dn.sql`** adds the indexes the DN and date lookups
need, and makes a user DN unique ignoring case. Before adding that constraint it
merges any user rows that differ only by DN case: the lowest id is kept, the
first non-empty value of each field wins, the highest role wins, and favourite
groups, alternate managers and group permissions are combined. The number of
merged rows is printed.

1. Start the application against the database once, so the tables exist (the
   script stops with an error if they don't).
2. Back up the database.
3. Run the script:

   ```bash
   psql "postgresql://USER:PASSWORD@HOST:5432/DB" -v ON_ERROR_STOP=1 -f db/migrations/001_indexes_and_unique_user_dn.sql
   ```

   Or, for a database in a container:

   ```bash
   docker exec -i POSTGRES_CONTAINER psql -U USER -d DB -v ON_ERROR_STOP=1 < db/migrations/001_indexes_and_unique_user_dn.sql
   ```

- Do **not** add `--single-transaction` (`-1`). The indexes are built with
  `CREATE INDEX CONCURRENTLY`, so the app can keep running and writing while they
  build, and that cannot happen inside a transaction. Only the short merge step
  takes a lock, and only on `user_records`.
- The script is safe to run again. Existing indexes are skipped, and an index
  left invalid by an interrupted run is dropped and rebuilt.
- Deploy the application version that expects the unique DN (it handles the
  constraint when two requests create the same user at once) with or after the
  migration.

---

## Performance and tuning

Defaults are in `application.yml`; override them per environment as usual.

| Setting | Default | Purpose |
|---|---|---|
| `application.ldap.pooled` | `true` | Reuse LDAP connections instead of opening one per search. Pool size and idle timeout come from the JDK's `com.sun.jndi.ldap.connect.pool.*` system properties (defaults: 20 connections, 5 minutes; plain and SSL). Logins always use a fresh connection. |
| `spring.ldap.base-environment` `com.sun.jndi.ldap.connect.timeout` / `read.timeout` | 5 s / 30 s | A stalled directory fails the request instead of hanging it. |
| `application.ldap.page-size` | `500` | Large searches read results in pages, so they are not silently cut off at the directory's size limit (Active Directory 1000, OpenLDAP 500). `0` turns paging off for a directory without paged results. |
| `application.ldap.group-membership-cache-seconds` | `300` | How long a user's groups are cached. Permission checks read them on every request, so a change in the directory can take this long to show. `0` turns the cache off. |
| `spring.jpa.open-in-view` | `false` | Database connections are released when the service call ends, not when the page finishes rendering. |
| `spring.task.scheduling.pool.size` | `3` | The background jobs run on their own threads and never overlap with themselves. With more than one app instance, each instance still runs every job. |

### Measuring

**Per request.** Set `application.diagnostics.request-metrics: true` and every
page or API call logs how many SQL statements and LDAP operations it made, in
this form:

```
GET /app/home -> 200: <n> SQL statement(s), <n> LDAP operation(s), <n>ms
```

Leave it off in normal running.

**Queries at volume.** `db/perf/seed_volume.sql` fills an empty, throwaway
database with realistic data (by default 5,000 users over 90 days, about
1.2 million check-in rows) and refuses to run if the database already has users.
To seed a fresh Postgres in a container, measure the main queries before and
after the migration, and write a report to
`build/reports/perf/query-performance.md` (needs Docker; takes a few minutes):

```bash
PERF_MEASURE=1 ./gradlew test --tests '*QueryPerformanceMeasurement'
```

---

## Project layout

```
src/main/java/com/winllc/innoutwork/
  config/      Spring configuration, ApplicationProperties, SecurityConfig
  controller/  Thymeleaf page controllers (/app/**)
  rest/        JSON APIs (/api/**)
  service/     Business logic; LdapService, CheckInOutService, OrgChartService
  model/       JPA entities
  repository/  Spring Data repositories
  data/        DTOs, form objects, chart and report shapes
  cron/        Scheduled jobs
  security/    AppUserDetailsService, PermissionEvaluator
powershell/    Windows client + scheduled task definitions
product-site/  Product page: static site, nginx config and container
test/          Docker Compose stack, seed script, local CA
```

---

## A note on committed credentials

This repository contains development credentials in source control: PKCS#12
keystores under `src/main/resources/` with their passwords in `application.yml`,
and the local CA's signing keys and password under `test/step-ca-data/`.

They exist so the test stack works out of the box. Treat them as compromised —
generate fresh key material for anything real, and supply production settings
through environment variables or an external config rather than editing
`application.yml`.
