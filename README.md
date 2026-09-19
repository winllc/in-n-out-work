# in-n-out-work

Attendance and presence tracking for an LDAP-backed organization. Windows
workstations report logon, logoff, screen lock and screen unlock events to a
Spring Boot server, which turns them into per-user daily attendance, group
rollups, an org chart and absence notifications.

- **Server** — Spring Boot 4 / Java 21, Thymeleaf UI, PostgreSQL, LDAP directory
- **Client** — a PowerShell script driven by Windows Task Scheduler
- **Auth** — mutual TLS with X.509 client certificates, falling back to LDAP
  username/password

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

## Tests

```bash
./gradlew test
```

JUnit 5 + Mockito, with `@WebMvcTest` slices for the controllers.

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

| Action | Endpoint | Certificate | Runs as |
|---|---|---|---|
| `login` | `POST /api/check/in` | required | the user |
| `lock` | `POST /api/check/lock` | required | the user |
| `unlock` | `POST /api/check/unlock` | required | the user |
| `logout` | `POST /api/check/out` | none | SYSTEM |

Install the script and register all four scheduled tasks from an **elevated**
prompt:

```bash
cd powershell; .\Install-InOutWorker.ps1 -BaseUrl "https://your-server:8444/api/check"
```

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
