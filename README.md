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

## Install

Customers install ReadyRoom in one of two supported ways. Both are described step by step in the
installation guide: https://winllc.github.io/ens-apps-documentation/readyroom/install/

| | What | Where it comes from |
|---|---|---|
| **Release package** | `readyroom-<version>.tar.gz`: Docker Compose deployment of ReadyRoom and PostgreSQL 16 with `install.sh`, `upgrade.sh`, `backup.sh`, `restore.sh`, `env.template`, the Windows client and the container images (works air-gapped) | The signed release download (see [Releases](#releases)); sources in [`deploy/bundle/`](deploy/bundle/) |
| **Helm chart** | `readyroom` for Kubernetes and OpenShift, against your PostgreSQL (or an embedded one for evaluation) | `oci://ghcr.io/winllc/charts/readyroom`, or `readyroom-helm-<version>.tgz` in the release download; sources and every value in [`deploy/helm/readyroom/`](deploy/helm/readyroom/README.md) |

```bash
# Release package
tar xzf readyroom-1.2.3.tar.gz && cd readyroom-1.2.3 && ./install.sh

# Helm
helm install readyroom oci://ghcr.io/winllc/charts/readyroom --version 1.2.3 -n readyroom --create-namespace -f my-values.yaml
```

Either way ReadyRoom needs your LDAP directory or Active Directory, an SMTP server, and an ENS
Solutions license, uploaded on the license screen (`/app/admin/license`) or supplied at install time.
Building from source is for contributors only: see [Development](#development).

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

`SecurityConfig` prefers a client certificate. By default the **full subject DN is
the user's directory DN**, which `AppUserDetailsService` resolves against LDAP; a
filter on the certificate's UPN, EDIPI, e-mail or UUID can be configured instead,
along with an issuer allowlist, revocation checking and certificates forwarded by a
TLS-terminating proxy (see [Client certificates](#client-certificates-cacpiv)).
Browsers without a certificate fall back to the branded form login at `/login`,
validated by the LDAP authentication provider.

Browsers can also sign in through an identity provider, OpenID Connect or SAML 2.0
(Entra ID, Okta, ADFS), mapped to the same directory users and roles; off until
configured, see [Single sign-on](#single-sign-on-openid-connect-saml).

The Windows client's check-in, lock and unlock calls can also sign in with the
user's Windows logon (Kerberos). This is off by default; see
[Windows authentication](#windows-authentication).

Everything requires authentication except:

| Path | Why |
|---|---|
| `/api/check/out` | Runs at logoff, after the user profile — and their certificate store — is gone |
| `/actuator/health` | Health checks and probes; the only actuator endpoint exposed (others need `ADMIN`) |
| `/login`, `/error` | Sign-in and error pages |

That single exception drives the client design: since logoff cannot authenticate,
the answer to the login carries a server-issued `sessionId`, which the client caches
and echoes back with the logout. The server takes it as the user's identity only on
`/api/check/out`, only for a check-in's id that no logout has used yet, and only for
`application.logoff.session-max-age-hours` (168, a week) after the logon; a signed-in
logout with someone else's id is refused. The id is also bound to the client address
the logon came from: a logout presenting it from another address is refused like an
unknown id and audited as `auth.login` failure `session from another address` (with
the owner and the logon's address), so an id copied off a workstation cannot log its
user off from elsewhere (`application.logoff.bind-to-address`, on by default; see
*Upgrading the logoff binding* below). A logout with a usable id is always accepted;
only refused ones count towards a per-address limit
(`application.logoff.refused-per-minute`, 30), past which they get 429 and are no
longer audited one by one. Every other call records the signed-in user, whatever the
body says, and no other response or page shows a `sessionId`.

The binding is to the address ReadyRoom sees: behind a reverse proxy with
`SERVER_FORWARD_HEADERS_STRATEGY` set, the one its `X-Forwarded-For` reports, so the
proxy must set that header itself rather than pass on a client's. Workstations behind
one NAT address share it, so there the id is bound to that network, not the machine.
The logoff runs as SYSTEM with no client certificate (the user's is in their unloaded
profile) and no Windows ticket, so there is no certificate or account to bind it to; the
address is what the server can check. A workstation whose address changed between
logon and logoff (a roaming laptop, a VPN reconnect) has its logoff refused; where
that matters more, set `application.logoff.bind-to-address=false`
(`APPLICATION_LOGOFF_BIND_TO_ADDRESS`).

**Upgrading the logoff binding.** Nothing changes on the workstations: the client
sends what it always has, and the address is taken from the connection. Check-ins
made before the upgrade have no recorded address; the migration
(`V5__logoff_client_address.sql`) marks those from the last 90 days as valid from any
address, so the ids already cached in agents' session files keep working, once, until
they expire (`session-max-age-hours`, a week by default). Every logon after the
upgrade is bound. During a rolling upgrade, a check-in an old replica stores after
the migration has no address and its logoff is refused; the user's next logon is
bound as usual.

Every other post, put and delete needs a CSRF token: forms built with `th:action`
carry it, and `layout.html` adds it to the pages' `fetch()` calls from its `_csrf`
meta tags. The Windows client's `/api/check/**` is exempt. The session cookie is
`SameSite=Lax` and `HttpOnly`, and `Secure` with `SSL_ENABLED=true` or
`ENS_COOKIE_SECURE=true` (for a proxy that terminates TLS; `SESSION_COOKIE_SECURE`
is the older name and still works).

### Browser security headers

Every response carries (`web.SecurityHeadersFilter`):

| Header | Value |
|---|---|
| `Content-Security-Policy` | `default-src 'self'; script-src 'self' 'nonce-…'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'` |
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains`, on HTTPS requests only |
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `Permissions-Policy` | `camera=(), microphone=(), geolocation=(), payment=()` |

Scripts run only from ReadyRoom itself or inline with the request's nonce: templates
mark each inline `<script>` with `th:attr="nonce=${cspNonce}"`, and inline event
handlers (`onclick=` and the like) are not used; `static/js/behaviours.js` provides
`data-confirm` on forms and `data-hide-on-error` on images instead.
`ContentSecurityPolicyTemplatesTest` fails the build on a template that breaks
either rule.

| Variable | Default | Purpose |
|---|---|---|
| `ENS_CSP_REPORT_ONLY` | `false` | `true` sends `Content-Security-Policy-Report-Only` instead: browsers report what the policy would block but do not block it. An escape hatch for a page the policy breaks; warned at startup. |
| `ENS_COOKIE_SECURE` | `false` | Always mark the session cookie `Secure` (it is anyway with `SSL_ENABLED=true`) |
| `SERVER_FORWARD_HEADERS_STRATEGY` | (none) | `framework` behind a proxy that terminates TLS: ReadyRoom then takes the scheme, host and client address from `X-Forwarded-*`, so it knows a request was HTTPS. Only when every request comes through that proxy. The Helm chart sets it, and `ENS_COOKIE_SECURE=true`, whenever an ingress or route terminates TLS. |

### Notice-and-consent banner

For DoD and other U.S. Government sites (DISA STIG), ReadyRoom can show the notice-and-consent banner
before any sign-in. It is enforced on the server (`security.ConsentFilter`): while it is on, nothing signs
in — the password form, a client certificate, anything — until the browser session has pressed **I agree**
on `/consent`. Before that, pages redirect to the banner, browser API calls get `401`
(`{"error":"consent_required"}`), and a client certificate is ignored. Agreeing is recorded in the session
and audited as `auth.consent-acknowledged`; signing out, or the session ending, means agreeing again.

Machine endpoints are not browser sessions and are exempt: the Windows client's API (`/api/check/**`), the
audit pull API (`/api/audit/events`, bearer tokens), `/actuator/health` and, on the management port,
`/actuator/prometheus`.

| Variable | Default | Purpose |
|---|---|---|
| `ENS_CONSENT_BANNER_ENABLED` | `false` | Show and enforce the banner before sign-in |
| `ENS_CONSENT_BANNER_TITLE` | `U.S. Government (USG) Information System (IS) Notice and Consent` | |
| `ENS_CONSENT_BANNER_TEXT` | the standard DoD notice-and-consent text | Plain text; blank lines separate paragraphs |
| `ENS_CONSENT_BANNER_FILE` | (none) | Read the text from this file instead (wins over `_TEXT`); an unreadable file stops startup |

### Session lifetime

Signed-in browser sessions end after a period of inactivity, at an absolute limit after sign-in however
busy, and optionally when the user has too many (`security.SessionControls`). An ended session goes back
to sign-in (`/login?expired`; API calls get `401`) and is audited as `auth.session-expired` with `reason`
`idle`, `absolute` or `concurrent`. The Windows client's API is exempt.

| Variable | Default | Purpose |
|---|---|---|
| `ENS_SESSION_IDLE_TIMEOUT` | `30m` | Idle timeout (a Spring duration: `15m`, `1h`) |
| `ENS_SESSION_ADMIN_IDLE_TIMEOUT` | same as idle | A shorter idle timeout for administrators (STIG suggests `15m`) |
| `ENS_SESSION_ABSOLUTE_TIMEOUT` | `12h` | A session ends this long after sign-in |
| `ENS_SESSION_MAX_PER_USER` | `0` (no limit) | Concurrent sessions per user; past it the oldest is ended (STIG suggests `3`) |

Sessions are kept in the database (Spring Session JDBC, tables from Flyway `V6`), so any replica serves any
request, a restart or rolling update does not sign anyone out, and the per-user limit counts sessions on every
replica. Expired sessions are deleted once a minute, on one replica (`security.SessionExpiry`), which also
audits each idle expiry. The cookie keeps its name, `JSESSIONID`. Session attributes are read back only if they are made of the classes sessions hold (the JDK's
plain values and collections, java.time, Spring Security's and ReadyRoom's own types; never `java.rmi` or
`java.net`): a row written by anyone with database access cannot make ReadyRoom instantiate other classes, and a
refused value counts as absent. A session attribute a replica of another
release cannot read back (during a rolling update) is treated as absent: at worst that person signs in again.

Timeouts under a minute, or a negative limit, stop startup with the setting's name. ReadyRoom had no idle
setting of its own before; Spring Boot's `SERVER_SERVLET_SESSION_TIMEOUT` no longer decides how long a
signed-in session lasts, `ENS_SESSION_IDLE_TIMEOUT` does.

### Roles

`USER`, `MANAGER`, `ADMIN` (`UserRoleEnum`). Roles open pages (metrics, settings and
group reports need `MANAGER` or `ADMIN`); whose data a page shows is decided by
`AccessPolicy`, in `@PreAuthorize` expressions and the controllers:

- A person's details (arrival, departure and status-change times, notes, average
  login time, check-in history, calendar, alternate managers): themselves; `ADMIN`;
  and the people the viewer is responsible for, whatever their role: members of the
  groups they manage (the group's directory `owner` or an alternate manager set in
  ReadyRoom), their direct reports in the directory, and the people they are a delegate of
  (see [Delegates](#delegates)). Indirect reports are not included.
- Everyone else, as a coworker: found by search, a group or the org chart, with their
  directory details, never their times, notes or history. How much of their day a coworker
  sees is `application.privacy.coworker-visibility` (see [Privacy and retention](#privacy-and-retention)):
  by default whether they are in and the day's status, a status a manager recorded (absent,
  unscheduled leave) showing as `OUT_OF_OFFICE`.
- Groups: opened by their members, their managers and `ADMIN`; managed (managers,
  report, access) only by `ADMIN` and their current managers.
- Absence notifications: the manager they were sent to, and `ADMIN`.

A principal with no directory user (a machine certificate, say) does not sign in.

### Delegates

A person's delegates (alternate managers) are, besides their manager in the directory, who receives their absence
alerts, may answer them, decides their status requests and sees their details. **Only administrators set them**: on
the person's details page, or with `POST /api/users/managers/update/{dn}` (a JSON list of DNs; each must be someone
else in the directory). People used to pick their own on their profile; the profile now only lists them.

*Administration &rarr; Delegates* (`/app/admin/delegates`) lists everyone with delegates. Delegates people chose for
themselves before this release are kept, so an upgrade does not silently change who receives anyone's alerts, but
are marked "to review": an administrator keeps them, changes them on the details page, or removes them. While any
wait, startup logs at INFO how many (`N people have delegates (alternate managers) chosen before administrators
managed them ...`). Changes are audited as `person.updated` with `details.via` `alternate-managers` (old and new
list) or `alternate-managers-reviewed`.

### Statuses and approvals

People mark days on their calendar (the profile page) with a status. Each status is in one of three classes
(`service.StatusPolicy`), enforced by the server on every API that sets one, whatever a page offers:

| Class | Statuses (default) | Who, and how |
|---|---|---|
| Set by the person | Normal, Early Leave, Late Arrival | The person, on their own calendar; takes effect at once and excuses nothing (a late arrival only moves the time they are expected by) |
| Requested | Out of Office, Work From Home, TDY, Scheduled Leave, Absent Excused (`application.events.request-statuses`) | The person asks; their manager approves or rejects. Until approved it is not on the calendar, so the absence alerts treat the day as not excused |
| Recorded by managers | Absent, Absent Unexcused, Unscheduled Leave | Only an administrator, or the person's manager or delegate, for someone else (also by answering an absence alert) |

A request is `requested`, then `approved`, `rejected` or `cancelled`. **Who decides:** the person's manager in the
directory, their delegates (alternate managers, which administrators set) and any administrator; nobody decides
their own, administrators included. An approval writes the status on each day of the request (replacing what the
person had set there); a rejection writes nothing. The requester can cancel a pending request, or an approved one
until its last day has passed, which takes its days from today on off the calendar (an administrator can cancel
anyone's). Managers, delegates and administrators can also record any status directly for someone they decide for: by dragging
across days on that person's calendar (their details page), or `POST /api/event/update` with `dn`.

**Where:** dragging across days on the profile calendar (a requested status says it needs approval), the
*Approvals* page (`/app/approvals`: what waits for you, your own requests, a form to ask), and the API:

| Call | What |
|---|---|
| `POST /api/requests` | Ask: `status`, `fromDate`, `toDate` (the day after the last), optional `note` (500 characters, shown to approvers); 201 with the request |
| `GET /api/requests/mine`, `/pending` | Your requests; the pending ones you may decide |
| `POST /api/requests/{id}/approve`, `/reject` | Optional `{"comment": "..."}`; 403 unless you decide for the requester, 409 once decided |
| `POST /api/requests/{id}/cancel` | The requester or an administrator |
| `GET /api/event/statuses` | The statuses you may put on your own calendar, and which need approval |

**Mail.** With a mail server configured, a new request is e-mailed to the requester's manager and delegates, a
decision to the requester, and a cancellation to the approvers, once the change is committed and from a background
thread, through the same mail settings as absence alerts. The mails carry the status and dates, not the notes.

**Audit.** `calendar.request-created`, `calendar.request-approved`, `calendar.request-rejected` and
`calendar.request-cancelled`, with the request id, status, dates, day count and state (never the notes); a status
set directly stays `calendar.status-set` (`details.via` `manager` when set for someone else).

**Upgrading.** Statuses already on calendars keep their effect. From the upgrade on, people can no longer set
Out of Office, Work From Home, TDY or Scheduled Leave themselves (or Absent Excused through the API): the calendar
sends them as requests. To let people set one of them without approval again, leave it out of
`APPLICATION_EVENTS_REQUESTSTATUSES` (startup then warns that it excuses absences unapproved).

### Privacy and retention

**What coworkers see.** `application.privacy.coworker-visibility` (`APPLICATION_PRIVACY_COWORKERVISIBILITY`) sets what
someone sees of the people whose details they may not see (see [Roles](#roles)), on every page and API that lists
people: user search (`/api/users/search`), a group's members (`/api/users/group/{group}`, `/app/users/{group}`), an
organization's members (`/api/orgnodes/users/{org}`), a person's page (`/app/user/details/{dn}`) and the org chart
(`/app/orgchart`, `/api/orgnodes/hierarchy`). Who a person is (name, directory details, groups) is shown in every mode;
times, notes, average login time and history never are.

| Value | A coworker's status shows |
|---|---|
| `full` (default) | As before: in, away, out, or the day's calendar status (telework, leave, TDY ...), a manager-recorded status reading `OUT_OF_OFFICE` |
| `status` | `IN` (checked in or unlocked today, screen locked included) or `OUT`; no calendar status |
| `none` | Nothing: no status, and the org chart shows headcounts but not how many are present, except to the `ADMIN` and `MANAGER` roles (who see organization-wide counts on the metrics page anyway) |

Whatever the setting, people see themselves in full, managers the people they are responsible for (their reports,
their groups' members, the people they are a delegate of) and administrators everyone. Any other value stops startup
naming the setting.

**Retention.** ReadyRoom keeps everything by default, as it always has. Set `application.retention.events-days`
(`APPLICATION_RETENTION_EVENTSDAYS`) to keep check-in records, calendar statuses, status requests and absence alerts
for that many days (0, the default, keeps them forever; otherwise at least 31, since the home page, My Reports and
the average login time read the last 30 days). A job (`service.RetentionService`, on `application.retention.cron`,
`APPLICATION_RETENTION_CRON`, default `0 17 2 * * *`, 02:17 daily) then deletes what is older:

- check-in records and calendar statuses, after rolling them up into one summary per person per month
  (`attendance_summaries`): days checked in, time in (first check-in to last check-out each day, as the reports count
  it) and days per status. A later run adds to a month already summarised; nothing is counted twice;
- absence alerts (by the day they were raised) and status requests (once their last day is past), without a summary.

It works a day at a time, oldest first, 500 people per transaction, so it never holds a table for long; several
instances take turns (each batch locks the one row of `retention_state`). Each run is audited as `data.purged`
(category `data`, actor `system`) with its counts (`checkIns`, `unattributedCheckIns`, `statuses`, `notifications`,
`requests`, `summariesWritten`, `days`), the cutoff (`purgedBefore`) and `retentionDays`, and counted in
`ens_readyroom_retention_purged_total{kind}`; a failure is `job.failed` and the next run carries on.
**Audit events are not purged by this**: they have their own retention, `ENS_AUDIT_RETENTION_DAYS`.

*What reports show for purged periods.* Before the cutoff there are no per-day records: the group report's days show
no check-in times, durations or statuses there, and it adds a "Before <date>: monthly summaries" table with each
member's days checked in, time in and days per status for the months it covers. Calendars (profile, user details)
show nothing for purged days. The home page, My Reports and metrics read at most the last 30 days, which retention
never reaches. A person's summaries are in their export (below).


**Export.** Any signed-in user downloads everything ReadyRoom holds about them from *My data* (`/app/privacy`, in the
user menu): `GET /app/privacy/export`, a JSON file (`readyroom-personal-data/1`) with their profile (notes, preferred
and average login time, role, favourite groups, delegates and the directory fields copied at sign-in), group access
granted in ReadyRoom, whose delegate and which groups' alternate manager they are, every check-in record (without the
logoff session id, a credential; with the client address a check-in came from), calendar status, status request (with notes and comments), absence alert about
them, and monthly summary; times in ISO 8601. The page also says who sees what and for how long. Each download is
audited as `data.exported` (`details.report` `personal-data`, `requestedBy` `self`, with counts). Administrators can
download anyone's from *Administration &rarr; Privacy* (`/app/admin/privacy/export?dn=...`, `requestedBy` the
administrator). The audit trail is not in the export; an administrator exports a person's events from the audit log
page (filter by actor or target).

**Erasure.** On request, an administrator erases a person's data from *Administration &rarr; Privacy*
(`/app/admin/privacy`, or *Export or erase data* on their details page), in two steps: choosing the person shows
what is held about them and whether they are still in the directory, with a form carrying a one-time token (10
minutes, that person only, in the administrator's session); erasing needs that token and the person's name typed
again, and the browser asks once more. Nobody erases themselves. It deletes, in one transaction, their user record
and group access, check-ins, calendar statuses, status requests, absence alerts about them or sent to them, and
monthly summaries, and takes them off other people's delegates and groups' alternate managers. It is audited as
`person.erased` (category `account`) with the counts and `inDirectory`. **Kept:** the audit trail (append-only and
hash-chained; its events name people by DN but hold no notes: profile notes changes are recorded as `notesChanged`
from this release, though events from earlier releases still carry the old and new text until audit retention
prunes them), and the person's DN where it stands in others' records as the one who decided a request or answered an
alert. **If the person is still in the directory** the page says so: ReadyRoom creates their account again (an empty
user record) at their next sign-in, and the hourly directory refresh does too, so remove or disable them in the
directory first.

Export and erasure are not licensed features and work in every license state, as the audit log does (erasure too
while read-only); demo mode refuses erasure like every other change.
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
| `logout` | `POST /api/check/out` | none | SYSTEM at logoff; the user on a remote disconnect |

`Authentication` in the config file (or `-Authentication`) chooses how login,
lock and unlock sign in:

| Value | Signs in with |
|---|---|
| `Certificate` | The user's client certificate. The default, and the only option before Windows sign-in existed. |
| `Windows` | The user's Windows logon. Needs Windows sign-in enabled on the server (see [Windows authentication](#windows-authentication)). |
| `Auto` | Windows first, then the certificate if that fails, for example on a laptop that cannot reach a domain controller. |

> **The server needs TLS for this.** Certificate sign-in cannot work over plain
> HTTP, and TLS is off in the defaults, so a server the Windows client talks to
> must terminate TLS itself (`SSL_ENABLED=true` with `docker-compose.tls.yml` in
> the release package, or `tls.enabled` in the Helm chart, reached through TLS
> passthrough or a LoadBalancer Service), or sit behind a proxy that terminates
> TLS, verifies the client certificate and forwards it in a header
> (`ENS_X509_SOURCE=header`; the Helm chart's `x509.source: header` with an
> edge-terminating ingress). The client needs no change for either: it speaks
> HTTPS with its certificate to whatever answers `BaseUrl`. Windows sign-in
> likewise: the ticket travels in an HTTP header.

Install the script and register the scheduled tasks (login, lock, unlock,
logout and disconnect) from an **elevated** prompt:

```bash
cd powershell; .\Install-InOutWorker.ps1 -BaseUrl "https://your-server:8181/api/check"
```

Add `-Authentication Windows` or `-Authentication Auto` to write that setting too.

Use `-WhatIf` on either script to see what would happen without changing
anything. The SYSTEM logoff task logs to `%ProgramData%\InOutWorker\logs\`;
the tasks that run as the user log to `%LOCALAPPDATA%\InOutWorker\logs\` in
their profile.

**Files SYSTEM touches.** The logoff task runs as SYSTEM, so nothing it writes,
deletes or reads may sit where an ordinary user can redirect it. The installer
makes the install folder and `%ProgramData%\InOutWorker` writable by SYSTEM and
Administrators only. Each user's login caches the server's `sessionId` in
`%ProgramData%\InOutWorker\sessions\<SID>.json`, readable only by that user,
SYSTEM and Administrators; in `sessions\` a user can add a file but not list,
change or delete anyone else's. The logoff task learns the user's SID from
event 4647, reads that file only after checking no part of its path is a
junction or link and that the user owns it, and never deletes it: the next
login overwrites it. A remote disconnect is reported by a task that runs as the
user. Configuration is read only from the install folder.

**Upgrading.** Re-run `Install-InOutWorker.ps1` on every workstation. Earlier
versions granted `Users` *Modify* on `%ProgramData%\InOutWorker`, which let a
user turn the SYSTEM task's file writes into writes anywhere on the machine;
the installer removes that grant, deletes the old shared `session.json`, sets
aside old `logs\` and `sessions\` folders as `*.old-<timestamp>`, and no longer
reads `%ProgramData%\InOutWorker\inoutworker.config.json` (it warns if one is
there; pass its settings to the installer instead).

Configuration comes from `inoutworker.config.json` next to the script (see
[`inoutworker.config.sample.json`](powershell/inoutworker.config.sample.json)),
overridden by explicit parameters — the script itself never needs editing per
machine.

> Server TLS validation is **on** by default. If your server uses a self-signed
> certificate, pass `-SkipCertificateCheck`, otherwise the tasks will fail.

**Tests.** `powershell/tests/` holds Pester tests for both scripts, and
`powershell/PSScriptAnalyzerSettings.psd1` the analyzer rules, including
compatibility with Windows PowerShell 5.1, which the tasks run. CI runs both on
Linux, and on Windows under Windows PowerShell 5.1 and PowerShell 7; to run
them yourself:

```bash
pwsh ./powershell/tests/Invoke-ClientChecks.ps1
```

It fetches the pinned Pester and PSScriptAnalyzer from the PowerShell Gallery
and checks their SHA-256 first. Off Windows, the tests that need real ACLs,
SIDs and junctions are skipped; the rest mock the Windows-only commands. Not
covered anywhere: the ACLs the installer writes and the task registration,
which need an elevated Windows run, and the server round trip.

The superseded `login.ps1`, `logout.ps1` and `updatestatus.ps1`, and the task
XMLs that ran them, have been removed: they accepted any server certificate.
Remove them, and any task that runs them, from workstations that still have
them.

---

## Licensing

ReadyRoom needs an ENS Solutions license, issued by the ENS licensing portal in the `ens-license/1`
format for the product `readyroom`, and checked offline against the ENS public key built into the
application (`license/EnsKeys.java`; deliberately not configurable). The verifier is the portal's own
zero-dependency library, copied unchanged into `src/main/java/com/enssolutions/license` (from the
products-page repository, `licensing/java`). No network is involved, so this works the same on an
air-gapped enclave.

**FIPS-mode Java runtimes.** The library is ens-license 1.2.0, which checks ECDSA P-256 signatures as well
as Ed25519. From this release ReadyRoom trusts two ENS production keys: the Ed25519 key
(`dabdc531a28916c5`) and the ECDSA P-256 key (`e0a2e4ff9c4f5bfc`). A Java runtime in FIPS mode (for
example Red Hat's OpenJDK with FIPS enabled, or BouncyCastle FIPS) has no Ed25519, so there it accepts
only licenses that carry a P-256 signature; the license screen then says the file is signed only with an
algorithm this runtime does not provide. Licenses issued before ENS enabled P-256 signing are
Ed25519-only: they keep working on ordinary runtimes, but a FIPS host needs a re-signed copy, downloaded
again from the ENS licensing portal or requested from ENS, that carries a P-256 signature. The license itself
now lives in the database; the license file and state directory are imported once on upgrade (see "Where the
license lives" below).

**What the license state means.**

| State | Mode | What works |
|---|---|---|
| `VALID`, `GRACE` (expired, within its grace days) | full | Everything the license's features include. In grace, and in the last 30 days before expiry, administrators see a renewal warning. |
| `EXPIRED` (past grace), `RELEASE_NOT_COVERED` (perpetual license, build newer than its maintenance) | read-only | Pages and GET calls work; anything else is refused with 403 (`license_read_only`), the Windows client's check-ins included. Scheduled jobs pause: directory sync, auto check-out, absence e-mails. |
| `MISSING`, `INVALID`, `WRONG_PRODUCT`, `WRONG_INSTALLATION`, `SUPERSEDED`, `NOT_YET_VALID` | unlicensed | Only sign-in/out, the license screen, `GET /api/license`, the audit log (`/app/admin/audit`) and its pull API (`/api/audit/events`), a person's data export and erasure (`/app/privacy/**`, `/app/admin/privacy/**`), static assets, actuator and the error page. Browsers are sent to the license screen; API callers get 403 JSON. |

An evaluation license shows an "Evaluation license" banner on every page.

**Features.** Every catalog feature is gated, including the ones every license carries today, so a
feature can move tier on the portal alone:

| Feature | License id | What it gates |
|---|---|---|
| Attendance tracking | `readyroom.attendance` | The Windows client's API (`/api/check/**`), a user's check-in/out audit log (`/api/users/checkinoutrecords/**`), the home dashboard's status, attendance and agent cards, auto check-out (`MarkInactiveCron`) and the directory sync (`RefreshUserRecordsCron`) |
| Calendar | `readyroom.calendar` | The profile and user-details calendars, `/api/event/**`, status requests (`/api/requests/**`, the Approvals page `/app/approvals`), the organization calendar (`/api/settings/calendar/**`), the dashboard's "Coming up" cards |
| Absence alerts | `readyroom.absence-alerts` | `SendAbsentNotificationsCron` |
| Metrics and reports | `readyroom.metrics` | `/app/metrics`, My Reports (`/app/user/reports`, `/api/users/reports`), the group report and its PDF export (`POST /app/group/report`), and the menu entries, buttons and links to them |
| Org chart | `readyroom.org-chart` | `/app/orgchart/**`, `/api/orgnodes/**`, the organization parse rules in Settings, the menu entry |
| Classification banners | `readyroom.classification-banners` | The top and bottom banner (`application.banner-text`); not rendered without it. Unlike the rest it stays up in read-only mode, since the data it marks is still on screen. |

Controllers and endpoints are marked `@RequiresFeature(LicensedFeature.X)` (`license/LicensedFeature.java`
lists them); jobs ask `LicensedJobs.mayRun`; templates hide menus, cards and buttons with
`licenseInfo.isEnabled('<id>')`. A refused API call gets 403 and

```json
{"error":"license_required","state":"VALID","feature":"metrics","message":"Metrics and reports is not included in this installation's license."}
```

(`error` is `license_required`, `license_read_only` or `license_limit`; `feature` only when one
feature is refused). Browsers get a "not licensed" page instead, or the license screen while there is
no usable license. API calls are never redirected.

**The Windows client** (`powershell/Update-WorkStatus.ps1`) makes one POST per logon, lock, unlock or
logoff and does not retry. A refusal makes it log the HTTP status and the server's `error` and
`message` to its log (see [Windows client](#windows-client)) and exit with code 3; the next event simply tries
again, so nothing loops. Because the API answers with JSON rather than a redirect, a refused event
can never be mistaken for success.

**Size.** A license covers a number of *tracked people*: user records. The license screen shows
`<count> of <limit> tracked people`. Up to 10% over the limit new people are still added, with a
warning in the log; beyond that new people are not added: the directory sync skips them (and adds
them on a later run once the license has room), and a new person's first check-in is refused with
`license_limit` (they can still sign in to the web pages, without a record being stored). Nobody
already tracked is ever removed. The limit applies only in full mode, so an administrator can always
sign in to install a license.

**The license screen** is *Administration &rarr; License* at `/app/admin/license`, for the `ADMIN`
authority (`application.super-user-dns`, or the ADMIN role). It shows the state and why, the licensee,
edition, expiry, grace and maintenance, every catalog feature ticked or not, the size in use, and this
installation's id (for example `RR-7F3K-29QD`), which customers enter on the portal to bind a license
to this installation. Upload a `.lic` file there (or paste it); it takes effect at once. A license for
another product, bound to another installation, or an older revision of one already installed is
refused with the reason, and the installed one stays. Anyone else sent there while there is no usable
license sees "ReadyRoom is not licensed, ask your administrator" (and can sign out).
`GET /api/license` reports the state to any signed-in user; administrators also get the installation
id and where the license is kept.

**Where the license lives.** In the database: the license, this installation's id and the highest
revision seen of each license (so an older file cannot be put back), in the `ens_license_*` tables (Flyway
`V8__ens_license_state.sql`, ens-license 1.2.0's `JdbcLicenseStore`). Every replica reads the same state,
an upload on one is in force on all within seconds, and a short database hiccup keeps the license read last
in force.

Earlier releases kept it in files, and on its first start this release imports them once, when the database
holds no installation id yet, so the installation keeps its id and licenses bound to it keep working. Two
environment variables (not Spring properties) say where they are:

| Variable | In the images | Outside a container (library default) |
|---|---|---|
| `ENS_LICENSE_FILE` | `/var/lib/ens/readyroom/license.lic` | `/etc/ens/readyroom.lic` |
| `ENS_LICENSE_STATE_DIR` | `/var/lib/ens/readyroom` | `/var/lib/ens/readyroom` |

If the database was migrated by an earlier release, holds no installation id yet, and
`ENS_LICENSE_STATE_DIR` has no `installation-id` to import (an upgrade straight to several replicas with the
license volume off), ReadyRoom does not make up a new id, which would silently break a license bound to the
old one: license requests fail and the log says how to fix it, mounting the old volume for one start, or
`ENS_LICENSE_NEW_INSTALLATION_ID=true` to accept a new id (honoured only while the database holds no license or
revisions, and logged at ERROR while set). With `ENS_LICENSE_EXPECT_INSTALLATION_ID=true`, which the chart sets
whenever it mounts no license volume, the same holds whatever the migration history: a fresh or rebuilt database
behind an existing installation runs every migration at once and would otherwise look like a new installation.

**Without the license volume** (several containers, `docker run` without `-v ...:/var/lib/ens/readyroom`), set
`ENS_LICENSE_EXPECT_INSTALLATION_ID=true` (the Helm chart does so itself whenever it mounts no license volume):
ReadyRoom then never makes up an installation id when its database has none, whatever the database's
migration history says, so a fresh or rebuilt database behind an existing installation cannot silently break a
bound license. The release bundle's compose files always mount the volume.

**Upgrading:** keep the license volume mounted for the first start of this release (the release bundle and
the chart do, unchanged); after that it is not read again and may be removed (the chart:
`persistence.license.enabled=false`, which several replicas need). Back up the database: it now holds the
installation id. `ENS_LICENSE` (the license itself, from a secret) works as before: it is installed when no
usable license is, or when it is a newer revision.

The release date that a perpetual license's maintenance is compared with is the build time from
Spring Boot's build-info (`springBoot { buildInfo() }` in `build.gradle`); without build-info (running
from an IDE) a perpetual license covers every build.

---

## Configuration

### Where the database and directory are

The defaults in `src/main/resources/application.yml` name the services in the
root `docker-compose.yml`, so the containerised stack needs no overrides at all:

```yaml
url: jdbc:postgresql://${DB_HOST:postgres}:${DB_PORT:5432}/${DB_NAME:appdb}
urls: ldap://${LDAP_HOST:openldap}:${LDAP_PORT:389}
```

Those names only resolve inside the compose network, so anywhere else supplies
its own values. Two ways, both without editing the file:

| Situation | How |
|---|---|
| The app on your host, services in compose | `--spring.profiles.active=local` (`application-local.yml`: localhost, published ports) |
| A real deployment (the release package's `.env`, or the Helm chart's values) | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `SPRING_LDAP_URLS` (`ldaps://dc1.example.com:636`), `LDAP_USERNAME`, `LDAP_PASSWORD` |

Use `ldaps://` for any real directory: the sign-in form checks users' passwords by binding to it, so over
plain `ldap://` (what `LDAP_HOST`/`LDAP_PORT` build, for the development stacks) they cross the network in
clear text, and ReadyRoom logs a WARN at startup saying so. ReadyRoom does not negotiate StartTLS.

### Port and TLS

The application listens on **8181 over plain HTTP** by default, so it starts and
is reachable with no certificate setup.

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8181` | Listen port |
| `SSL_ENABLED` | `false` | TLS; needs `SERVER_SSL_KEYSTORE` and, while `SERVER_SSL_CLIENTAUTH` is `want` (the default) or `need`, `SERVER_SSL_TRUSTSTORE` |
| `APPLICATION_BASE_URL` | `http://localhost:8181` | The URL notification emails link to |

**What is off with TLS.** Client-certificate (X.509) sign-in needs TLS, so with
the defaults the only way in is the LDAP username and password form, and the
Windows client's logon, lock and unlock calls cannot authenticate at all. Any
deployment relying on either needs `SSL_ENABLED=true` and its own keystore and
truststore (`SERVER_SSL_KEYSTORE`, `SERVER_SSL_TRUSTSTORE` and their passwords):
`docker-compose.tls.yml` in the release package, or the `tls` values of the Helm
chart. Nothing is packaged in the jar: with `SSL_ENABLED=true` and no key store,
or no trust store while client certificates are asked for, the application
refuses to start (`config.SslStoresCheck`) instead of falling back to a default.
Put only the CAs that issue your users' certificates in the truststore.

### Application settings

Server settings live under the `application:` prefix in
`src/main/resources/application.yml`, bound to `ApplicationProperties`. The
values that most often need changing per deployment:

| Property | Purpose |
|---|---|
| `user-base-dn` | Base DN for user searches |
| `user-ldap-filter` | Filter identifying user entries; defaults to `(objectclass=inetOrgPerson)`. Used alone to enumerate and count users, and combined into an AND for search, reports and the org chart, so it applies everywhere users are looked up. Parentheses are added if omitted. |
| `groups[].groups-base-dn` | One entry per top-level group tree shown in the UI |
| `super-user-dns` | DNs always treated as `ADMIN` (`APPLICATION_SUPERUSERDNS_0`, `_1`, ...). None are packaged; startup warns when there are none |
| `user-ldap-*-attribute` | Maps LDAP attributes to organization, location, branch, employee type, manager, duty sub-organization |
| `duty-sub-org-groups-base-dn` | Enables the org chart; blank disables it |
| `ldap.max-pages` | Most pages one paged search reads before returning what it has (default 1000). Bounds a directory that keeps returning a paging cookie, which would otherwise hold the request open indefinitely. |
| `checkOutAfterMinutes` | Minutes a session may stay locked before `MarkInactiveCron` checks it out (default 240; the packaged configuration used to say 2) |
| `retention.events-days` | Days to keep check-in records, statuses, requests and absence alerts (`APPLICATION_RETENTION_EVENTSDAYS`); `0` (default) keeps them forever, otherwise at least 31. See [Privacy and retention](#privacy-and-retention). |
| `retention.cron` | When the retention purge runs (`APPLICATION_RETENTION_CRON`, a six-field Spring cron, default `0 17 2 * * *`) |
| `privacy.coworker-visibility` | `full` (default), `status` or `none`: what people see of coworkers (`APPLICATION_PRIVACY_COWORKERVISIBILITY`); see [Privacy and retention](#privacy-and-retention) |
| `events.request-statuses` | The statuses people request rather than set (`APPLICATION_EVENTS_REQUESTSTATUSES`, comma-separated; default `OUT_OF_OFFICE,WORK_FROM_HOME,TDY,SCHEDULED_LEAVE,ABSENT_EXCUSED`, every status that excuses an absence). `STANDARD` is refused at startup. See [Statuses and approvals](#statuses-and-approvals). |
| `events.max-range-days` | The most days one call may set a status, a status request or an organization calendar event on (`APPLICATION_EVENTS_MAXRANGEDAYS`, default 366). Every API that creates events checks it and refuses a longer range with 400 (`range_too_long`) before writing anything. |
| `notification-sender-email` | The From address of absence alerts; required once `SPRING_MAIL_HOST` is set (startup stops without it) |
| `application-base-url` | Used in notification emails |

Two background jobs run on intervals set by `inactive-cron` and
`send-absent-notification-cron`: one auto-checks-out idle users, the other emails
absence notifications.

These settings are validated at startup: an impossible value (`checkOutAfterMinutes` 0, an
`application-base-url` that is not http(s), a sender that is not an e-mail address, a negative limit, an
empty `user-base-dn`, an unknown `ENS_AUDIT_SYSLOG_PROTOCOL`) stops ReadyRoom with the setting's name rather
than taking effect.

**Mail.** No mail server is packaged: set `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`,
`SPRING_MAIL_PASSWORD` and `APPLICATION_NOTIFICATIONSENDEREMAIL`; without a host absence alerts are recorded
but not e-mailed (warned at startup). STARTTLS is required when enabled, the server's certificate is checked
against the JVM's trust store, and connecting, reading and writing each time out after 30 seconds. Trusting
a mail server's certificate without checking it is an explicit opt-in:
`SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_TRUST=<mail host>` (or `*`, any; not FIPS- or STIG-appropriate).

### Org chart parsing

The org chart derives hierarchy from a single flat LDAP attribute. By default a
value splits on letter/digit boundaries — `RYS34B` becomes `RYS` → `34` → `B` —
and trees sharing a prefix merge into one. When that heuristic does not fit,
add an `OrgParseRuleRecord` under **Settings** with a regex whose capture groups
define the levels.

---

## Demo mode

A read-only walkthrough of the whole application, for showing it to people who have no account in
the directory. **Off unless switched on**, and off in the shipped configuration.

**Visitors still sign in.** The login page, the directory and the roles all work exactly as they
normally do. What demo mode changes is two things: nothing can be written, and the credentials for a
few seeded accounts are listed on the login page so someone with no account of their own can pick a
role and look around as it.

```yaml
application:
  demo:
    enabled: true
    banner: "DEMO - read only"
    accounts:
      - role: "Administrator"
        username: "demo"
        password: "password"
        description: "Everything: all groups, settings, reports and the org chart"
      - role: "Manager"
        username: "alice"
        password: "password"
        description: "Her own day, her direct reports, and the groups she manages"
      - role: "User"
        username: "bob"
        password: "password"
        description: "His own attendance and profile only"
```

`accounts` drives the tile on the login page; an empty list just means no tile. Clicking a row fills
the form rather than submitting it, so a stray click cannot sign someone in as an administrator.

The `role` label is for the visitor — **it grants nothing.** What each account can do comes from the
directory and `super-user-dns` as always, so a label that does not match the account is only ever
misleading. In the seeded data the three above are true: `demo` is listed under `super-user-dns`,
`alice` is given `user_role = MANAGER` by `test/seed-mock-data.sh` and is the directory manager of
everyone else, and `bob` is a plain user. `DemoProfileTest` checks that each advertised username is
one the seed script creates, that the passwords match the one it sets, and that the admin and manager
roles are actually granted somewhere.

### What is enforced, and what is not

- **Nothing can be changed.** POST, PUT, PATCH and DELETE are refused for every path. The rule is by
  method rather than by path, so an endpoint added later is covered without anyone remembering to
  come back to it, and so is anything reached directly with curl. The sign-in and sign-out posts are
  the two exceptions — they write nothing of the application's own, and without them there would be
  no way in.
- **Actuator is refused apart from health**, because a demo is usually reachable by people who should
  not be reading the application's configuration and this application exposes `env`.
- **A banner on every page**, and submit buttons on write forms are disabled. That part is a courtesy
  so visitors are not surprised by a refusal; it is not the boundary. The refusal is.

### Before you turn it on

Publishing credentials makes everything those accounts can see readable by anyone who can reach the
login page. Point a demo instance at its own directory and database holding data that is safe to
publish — the seeded stack below is a reasonable base — and never at production.

Two things demo mode does **not** stop:

- The scheduled jobs still run and still write (auto check-out, absence notifications). They are
  server-side and unrelated to what a visitor can do, but the data will move on its own.
- Signing in creates the account's `user_record` row on first request, exactly as any first sign-in
  does.

The demo stacks (containers, or `bootRun` with the `demo` profile) are under
[Development: the demo stack](#the-demo-stack-one-command).

| Setting | Meaning |
|---|---|
| `demo.enabled` | The only thing that turns demo mode on. Default `false`. |
| `demo.banner` | Banner text shown on every page. |
| `demo.accounts` | The rows of the login page's tile: `role`, `username`, `password`, `description`. Empty by default, so a deployment that never asked for demo mode publishes nothing even if accounts are configured. |

## Client certificates (CAC/PIV)

Client-certificate sign-in is on whenever ReadyRoom terminates TLS and asks for certificates
(`SSL_ENABLED=true`, `SERVER_SSL_CLIENTAUTH=want`), or whenever certificates come from a proxy
(`ENS_X509_SOURCE=header`). Every chain that accepts certificates — the browser pages, the Windows client's
check-ins with or without Windows sign-in, demo mode — applies the same rules (`security.x509`). Each refusal
is audited as a failed `auth.login` (`details.method` `x509`) with its reason: `certificate revoked`,
`revocation unknown`, `issuer not allowed`, `no single directory user matches`, `certificate has no upn`,
`proxy secret missing or wrong`, `certificate not issued by a trusted CA`, ...

**Who a certificate signs in as.** By default its subject DN must be the user's directory DN, as before.
`ENS_X509_USER_FILTER` finds the user with an LDAP filter instead, searched under `user-base-dn` together
with `user-ldap-filter`; exactly one entry must match, or nobody signs in. Values are LDAP-filter-escaped.

| Placeholder | Value |
|---|---|
| `{upn}` | The subject alternative name otherName UserPrincipalName (OID 1.3.6.1.4.1.311.20.2.3) |
| `{edipi}` | The DoD EDIPI: the digits before `@` of a UPN `1234567890@mil`, or else the trailing `.NNNNNNNNNN` of the CN (`LAST.FIRST.MI.1234567890`) |
| `{email}` | The rfc822Name subject alternative name, or else the subject's emailAddress |
| `{uuid}` | The UUID of a `urn:uuid:` URI subject alternative name (PIV FASC-N UUID), without the `urn:uuid:` |
| `{subject}`, `{cn}` | The subject DN (RFC 2253), its most specific CN |

For example `(userPrincipalName={upn})` for Active Directory and CAC, or
`(|(employeeNumber={edipi})(mail={email}))`.

**Revocation.** Off by default, for upgrades; with certificate sign-in on and revocation off, startup warns
that STIG requires it. OCSP asks the certificate's responder (AIA), or `ENS_X509_OCSP_RESPONDER_URL`; CRLs
come from the certificate's http(s) distribution points, or only from `ENS_X509_CRL_DIR` when that is set
(air-gapped sites: drop the CA's CRLs there; changed files are re-read). Network fetches time out after 5 s
(connect) and 10 s (response), follow no redirects, are capped at 1 MB (OCSP) and 20 MB (CRL), and never go
to a link-local or cloud metadata address. The JDK's PKIX validator judges every answer, configured for that
check only. The issuer's certificate must be known: in the chain the client sent, `ENS_X509_TRUSTED_CA_FILE`
or the TLS trust store.

**Certificates from a TLS-terminating proxy** (`ENS_X509_SOURCE=header`). The proxy (an ingress, a load
balancer) verifies the client certificate and forwards it, URL-encoded PEM as nginx's
`$ssl_client_escaped_cert` sends it, in `ENS_X509_HEADER`, together with the shared secret in
`X-ENS-Proxy-Secret`. ReadyRoom takes the certificate only from an address in `ENS_X509_TRUSTED_PROXIES`
with the right secret (compared in constant time), checks it chains to `ENS_X509_TRUSTED_CA_FILE` (or the
TLS trust store) and is valid, and otherwise ignores it (audited). A certificate on the connection itself is
not used in this mode. ReadyRoom refuses to start in header mode without a secret of 32 characters or more,
or without a CA. Let nothing but the proxy reach ReadyRoom's port. For nginx:

```nginx
ssl_client_certificate /etc/nginx/user-cas.pem;
ssl_verify_client optional;
proxy_set_header X-SSL-Client-Cert $ssl_client_escaped_cert;
proxy_set_header X-ENS-Proxy-Secret "<the secret>";
```

| Variable | Default | Purpose |
|---|---|---|
| `ENS_X509_SOURCE` | `servlet` | `servlet` (ReadyRoom terminates TLS) or `header` (a trusted proxy forwards the certificate) |
| `ENS_X509_HEADER` | `X-SSL-Client-Cert` | The header the certificate arrives in (ingress-nginx: `ssl-client-cert`) |
| `ENS_X509_TRUSTED_PROXIES` | `127.0.0.1/32;::1/128` | Addresses (CIDRs) allowed to send it, `;` or `,` separated |
| `ENS_X509_PROXY_SECRET_FILE` / `ENS_X509_PROXY_SECRET` | (none) | The secret the proxy sends in `X-ENS-Proxy-Secret`, 32 characters or more; the file wins. Required in header mode |
| `ENS_X509_TRUSTED_CA_FILE` | (none) | PEM certificates of the CAs that issue user certificates: header mode's chain check (required there unless the TLS trust store is configured), and issuers for revocation |
| `ENS_X509_USER_FILTER` | (none: subject DN = directory DN) | LDAP filter template finding the user |
| `ENS_X509_ALLOWED_ISSUERS` | (none: any trusted CA) | Issuer DNs whose certificates may sign in, `;` separated (DNs contain commas); others are refused even when a trusted CA chains to them |
| `ENS_X509_REVOCATION` | `off` | `off`, `ocsp`, `crl` or `ocsp-then-crl` |
| `ENS_X509_OCSP_RESPONDER_URL` | (the certificate's AIA) | A responder to ask instead, e.g. a local OCSP relay |
| `ENS_X509_CRL_DIR` | (none) | A directory of CRL files (DER or PEM); with it set, distribution points are never fetched |
| `ENS_X509_REVOCATION_FAIL` | `closed` | `closed` refuses a certificate whose status cannot be determined; `open` allows it with a WARN |
| `ENS_X509_REVOCATION_CACHE` | `10m` | How long a good or revoked answer is kept, per issuer and serial |

---

## Single sign-on (OpenID Connect, SAML)

Browsers can sign in through your identity provider instead of the directory password form: OpenID Connect
(authorization code flow with PKCE) and SAML 2.0 Web SSO, each off until enabled. Both can be on at once; the
sign-in page then shows a button for each next to the form, which keeps working, as do client certificates.
The Windows client is not affected: its API never goes to the identity provider.

**Who signs in.** Whoever the identity provider signs in must match exactly one directory user by the
protocol's user filter, an LDAP filter with the IdP's claims (OIDC) or attributes (SAML) as `{placeholders}`,
the same way `ENS_X509_USER_FILTER` maps a certificate. The values are escaped. No match, or several, is
refused. That directory user then gets exactly the roles any other sign-in would: `USER`, an
administrator-assigned role, `ADMIN` for `super-user-dns`. The IdP's groups add `MANAGER` or `ADMIN` only
through `ENS_SSO_ROLE_MAPPING`; a group not listed there grants nothing, whatever it is called.

| Variable | Default | Purpose |
|---|---|---|
| `ENS_SSO_ROLE_MAPPING` | empty | IdP group to role: `ReadyRoom Admins=ADMIN;ReadyRoom Managers=MANAGER` (`;` or new lines; matched ignoring case; the last `=` separates the role, so DN-style group names work) |
| `ENS_SSO_CLOCK_SKEW` | `60s` | How far the IdP's clock may be off for token and assertion times (at most 5 minutes) |
| `ENS_SSO_OIDC_ENABLED` | `false` | OpenID Connect |
| `ENS_SSO_OIDC_ISSUER_URI` | | The issuer; its discovery document is read on first use (not at startup, so an IdP outage does not stop ReadyRoom) |
| `ENS_SSO_OIDC_CLIENT_ID` | | Also the audience ID tokens must name |
| `ENS_SSO_OIDC_CLIENT_SECRET`, `_FILE` | empty | Empty: a public client (PKCE only). The file wins |
| `ENS_SSO_OIDC_SCOPES` | `openid,profile,email` | |
| `ENS_SSO_OIDC_USER_FILTER` | required | e.g. `(userPrincipalName={upn})`; see below on which claims to trust |
| `ENS_SSO_OIDC_GROUPS_CLAIM` | `groups` | |
| `ENS_SSO_OIDC_DISPLAY_NAME` | `Sign in with single sign-on` | The button |
| `ENS_SSO_SAML_ENABLED` | `false` | SAML 2.0 |
| `ENS_SSO_SAML_IDP_METADATA` | | The IdP's metadata: `https://...` (read on first use and every 12 hours, keeping the last good copy) or `file:/path` |
| `ENS_SSO_SAML_ENTITY_ID` | `{baseUrl}/saml2/service-provider-metadata/saml` | This service provider's entity id |
| `ENS_SSO_SAML_USER_FILTER` | required | `{nameid}` and attribute names, e.g. `(userPrincipalName={http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn})` |
| `ENS_SSO_SAML_GROUPS_ATTRIBUTE` | `http://schemas.microsoft.com/ws/2008/06/identity/claims/groups` | |
| `ENS_SSO_SAML_SP_KEY_FILE`, `_SP_CERT_FILE` | empty | PEM, PKCS#8 RSA key and certificate: signs requests and decrypts encrypted assertions, if the IdP wants either |
| `ENS_SSO_SAML_DISPLAY_NAME` | `Sign in with SAML single sign-on` | The button |

A missing required setting, a filter with no placeholder, or a role mapping to anything but `MANAGER` or
`ADMIN` stops startup with the setting's name. Map on an immutable claim the identity provider controls, such as the
UPN or an employee id, not one users can set themselves. An OIDC filter using `{email}` signs in only when the
ID token says `email_verified: true`; Entra ID's `email` claim, for example, is not verified.

**Endpoints to register at the identity provider.** `<base>` is `APPLICATION_BASE_URL`, which single sign-on
requires to be set to the absolute URL users reach (startup stops while it is the packaged default). Every
address below, and the SAML entity id (the audience assertions must name), is built from it and never from a
request's `Host`, `Forwarded` or `X-Forwarded-Host`, which a client can set: otherwise a response the IdP
issued to another service provider could be made to match. `ENS_SSO_SAML_ENTITY_ID`, when set, must be an
absolute URI.

| | |
|---|---|
| OIDC redirect URI | `<base>/login/oauth2/code/oidc` |
| SAML entity id / metadata | `<base>/saml2/service-provider-metadata/saml` (download it for the IdP) |
| SAML assertion consumer service (HTTP-POST) | `<base>/login/saml2/sso/saml` |

**Entra ID.** OIDC: register an app (web platform) with the redirect URI, create a client secret; issuer
`https://login.microsoftonline.com/<tenant id>/v2.0`. Add the `upn` optional claim to the ID token (*Token
configuration*) and use `(userPrincipalName={upn})`, or, where the directory holds the Entra object id (for
example AD's `msDS-ExternalDirectoryObjectId`, `User_<oid>`), `(msDS-ExternalDirectoryObjectId=User_{oid})`:
`oid` never changes. Do not map on `preferred_username`: Entra documents it as a display hint that can change
and is not guaranteed to be the UPN, nor on `email`, which is not verified.
For groups, add the *groups* claim (security groups) to the ID token; Entra sends group object ids, so map
those (`<object id>=ADMIN`). A user in more than 200 groups gets no `groups` claim (the overage case): assign
the app's groups explicitly ("Groups assigned to the application") or rely on ReadyRoom's own roles. SAML:
create an enterprise application, upload the SP metadata, use the *App Federation Metadata Url* as
`ENS_SSO_SAML_IDP_METADATA`, user filter on the `upn`/`name` attribute.

**Okta.** OIDC: a Web app integration with the redirect URI; issuer `https://<org>.okta.com` (or an
authorization server's); add a `groups` claim filtered to the ReadyRoom groups. SAML: an app integration with
the ACS URL and entity id above, NameID or an attribute carrying the user's login, a `groups` attribute
statement; the metadata URL from *Sign On*.

**ADFS.** SAML: add a relying party trust from the SP metadata URL (or file); claim rules sending UPN (and
*Token-Groups* as `http://schemas.microsoft.com/ws/2008/06/identity/claims/groups` or *Group*); metadata
`https://<adfs>/FederationMetadata/2007-06/FederationMetadata.xml`. OIDC (ADFS 2016 and later): an
application group with a server application; issuer `https://<adfs>/adfs`; filter on `{upn}`.

**What is checked.** OIDC: the state, the nonce, PKCE (always, also with a client secret), the ID token's
signature against the issuer's published keys, its issuer, audience and times. Access and refresh tokens are
not kept. SAML: the response's or assertion's signature against the metadata's certificates, issuer,
audience, destination and recipient, and times; the response must answer a request this browser started
(`InResponseTo`; IdP-initiated sign-in is refused); each assertion is used once, across replicas
(`sso_assertion_seen`). The SAML answer is a cross-site POST, on which browsers leave out the `SameSite=Lax`
session cookie, so the request it answers is kept in the database by its RelayState
(`sso_saml_request`, Flyway `V7`, ten minutes) and tied to the browser by a cookie of its own
(`ENS_SAML_<relay state>`, `SameSite=None; Secure`, only sent to the assertion consumer service): SAML needs
HTTPS. ReadyRoom asks the IdP's HTTP-Redirect sign-in endpoint (the POST binding would need a self-submitting
form, which the Content-Security-Policy blocks). The consent banner, when on, must be acknowledged before
either method starts.

**Audit.** Every sign-in is `auth.login` with `method` `oidc` or `saml2` and `idpRoles`, the roles the IdP's groups granted through `ENS_SSO_ROLE_MAPPING`; every refusal is a failed
`auth.login` with the reason (the IdP's error, the check that failed, `no single directory user matches`...).

**Not included.** Sign-out ends the ReadyRoom session only, not the one at the identity provider (no
single logout). Demo mode does not offer single sign-on.

## Several replicas, upgrades and outages

ReadyRoom can run as several replicas behind one Service, and a rolling update keeps one serving throughout,
so the Windows client's events, which it sends once and never retries, are not lost during an upgrade.

| Shared state | Where it lives |
|---|---|
| Browser sessions | The database (Spring Session JDBC); see [Session lifetime](#session-lifetime) |
| Scheduled jobs | Run on one replica at a time: each holds a lock in the database (ShedLock, table `shedlock`) while it runs: the directory sync, auto check-out, absence alerts, the retention purge, audit retention and session clean-up. A replica that finds a job's lock held skips that run. The lock is kept alive while a run lasts, kept for 90% of the job's interval after a quick run (so the job still runs about once an interval), and lapses within an interval if its replica dies |
| Audit forwarding (Splunk, syslog) | Every replica runs a forwarder; each batch is delivered under a lock, so none goes out twice |
| License state | The database (see [Licensing](#licensing)) |
| License mode changes | Each replica notices; the change is recorded once |

What stays per replica, by design: the access-denial summaries (each replica collapses and records its own),
the limit on refused logoffs per client address (`application.logoff.refused-per-minute` applies per replica,
so with N replicas an address may get up to N times as many refusals a minute before 429; it only bounds
noise), and caches that are re-read on their own (directory users and groups, revocation answers and CRLs).

**Stopping a replica** (an update, a scale-down, a node drain): it leaves the Service first (the chart's
`preStop` sleep), then finishes requests in progress (`server.shutdown: graceful`) and lets a scheduled job
already running finish and release its lock (`spring.task.scheduling.shutdown`), within the pod's grace
period. A replica killed mid-job leaves its lock to lapse: the others skip that job until then (a delayed
run, never a duplicate).

**Outages and absence alerts.** Every replica updates a heartbeat in the database every 15 seconds. When the
whole service was down, which with a single replica includes every upgrade with the `Recreate` strategy, the
gap is recorded (`service_outage`), since check-ins sent during it were lost. Then:

- someone whose check-in time plus grace fell after the outage began is not alerted about until the outage's
  end plus `application.ha.outage-grace` (`APPLICATION_HA_OUTAGEGRACE`, `30m`; `0` turns this off), so their
  next logon or unlock (which checks them in) can arrive first;
- a session last seen locked before the outage is checked out automatically only `checkOutAfterMinutes` after
  the outage's end, since its unlock may have been lost.

With two replicas and rolling updates there is no such gap, and alerts go out on time. The real fix for lost
events is the client's offline queue (planned).

**Upgrading to this release.** Nothing to do by hand. Flyway adds the tables (`V6` sessions, job locks,
heartbeat; `V7` single sign-on; `V8` license state). Everyone signs in again once, as at any restart. The
license volume is imported on the first start (keep it mounted then). The chart keeps one replica and, while
the license is on a ReadWriteOnce volume, the `Recreate` strategy; to run several replicas, see the chart's
README ("Several replicas"). With the release bundle (Docker Compose) there is one instance as before.

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
the keytab read-only at `/etc/in-n-out/http.keytab`, never bake it into the image.
The release package does this with `docker-compose.windows-auth.yml` (files in
`kerberos/`); the Helm chart with `windowsAuth.keytab.secretName` (a Secret
holding `http.keytab`) and optionally `windowsAuth.krb5Conf`. The container user
must be able to read the file. To check the keytab inside the container:

```bash
docker compose exec app klist -kte /etc/in-n-out/http.keytab              # release package
kubectl exec deploy/readyroom -- klist -kte /etc/in-n-out/http.keytab     # Helm
```

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

## Audit logging

ReadyRoom keeps a tamper-evident audit trail in the shared ENS format `ens-audit/1` (one JSON object
per event, the same in every ENS product), and can hand it to Splunk or any SIEM without custom
parsing. The full format and the Splunk setup are on the ENS documentation site,
[`audit-logging.md`](https://github.com/winllc/ens-apps-documentation/blob/master/docs/audit-logging.md) ("Audit logging").

**Where it lives.** Every event is stored, synchronously, in the `audit_events` table of ReadyRoom's
own PostgreSQL database, in the request that caused it. Each one carries a gapless `seq` and a SHA-256
hash chained to the previous event's, so a changed, removed or reordered event is detected by
verification. Writers are serialized by a row lock on the single-row `audit_chain_head` table, which
holds across application instances. A security change that cannot be audited does not happen: role,
permission, settings and calendar changes are committed together with their event, a sign-in fails if
its event cannot be stored, and a license upload is refused while the store is unavailable. The table is
append-only in the database itself: a trigger refuses every UPDATE (see [Database migrations](#database-migrations)).

**What is recorded** (action ids; never renamed):

| Category | Actions |
|---|---|
| authentication | `auth.login` (success or failure, `details.method` `ldap`, `x509`, `sso` (Windows), `api-token`, `session-id`; the attempted name on failure), `auth.logout`, `auth.consent-acknowledged` (the notice-and-consent banner, with the client address), `auth.session-expired` (`reason` `idle`, `absolute` or `concurrent`) |
| authorization | `access.denied` for every 403: role refused (`permission_denied`), `license_required`, `license_read_only`, `feature_not_licensed`, `license_limit`, `demo_read_only`. Identical denials (same user, path and reason) within 60 seconds are one event with `details.count`. |
| account | `person.created` (a new tracked person, at first sign-in or lookup), `person.erased` (an administrator's erasure of their data, with counts), `account.role-changed`, `account.permission-granted`, `account.permission-revoked` |
| configuration | `settings.changed` (organization parse rules, old and new values), `settings.cache-evicted`, `calendar.org-event-created` / `-deleted` (the organization calendar and holidays), `group.managers-changed` |
| data | `data.purged` (each retention run, with counts), `person.updated` (profile notes changed, never their text, and expected login time, alternate managers), `calendar.status-set` (late arrival and other statuses set directly), `calendar.request-created`, `calendar.request-approved`, `calendar.request-rejected`, `calendar.request-cancelled` (status requests), `absence.alert-responded`, `absence.alerts-dismissed`, `data.exported` (the metrics report, group reports and their PDF download, a person's data export) |
| license | `license.installed`, `license.rejected`, `license.mode-changed` (also across restarts), `license.limit-exceeded` |
| system | `system.started`, `system.stopped`, `job.completed` / `job.failed` (one per run, with counts and duration), `job.skipped` (once per license mode change) |
| audit | `audit.exported`, `audit.verified`, `audit.token-created`, `audit.token-revoked`, `audit.pruned`, `audit.delivery-failed`, `audit.delivery-recovered` |

The Windows client's logon, lock, unlock and logoff events are attendance data, kept in the check-in
records as before, and are not audit events. What is audited about the client API is its failed
sign-ins: no certificate, a refused Windows ticket, an unknown logoff session id (collapsed per source
like denials; past `application.logoff.refused-per-minute` a source's refusals are recorded once, as
`too many unknown sessions`). The two jobs that run every few minutes (auto check-out and absence alerts) record
`job.completed` only for runs that did something; every failure is recorded. Secrets never enter the
trail: passwords, tokens, keys, license text and session ids are recorded as `"[redacted]"` (the session
appears only as the first 16 hex characters of its SHA-256).

**The audit log page** is *Administration &rarr; Audit log* at `/app/admin/audit`, for the `ADMIN`
authority, as for the license screen. It is not a licensed feature and opens in every license state,
read-only and unlicensed included. It searches the whole trail, newest first, by time, category,
action, outcome, severity, actor, target, source IP and free text; a row opens every field of the
event, its formatted JSON, seq and hashes. From there administrators can export the current filter (up
to 100,000 events) as CSV or NDJSON, verify the chain (intact, or the first broken seq; after retention
pruning, verification starts from the oldest retained event and says so), see each output's delivery
status, and create or revoke pull-API tokens. Exports and verifications are audited.

**Outputs.** All optional except the database; configured with environment variables (Spring binds
them as `ens.audit.*`). Startup logs one line per output saying whether it is enabled.

| Variable | Default | |
|---|---|---|
| `ENS_AUDIT_STDOUT` | `true` | Each event as one raw JSON line on stdout, through the logger `ens.audit` with its own appender (no log prefix, not additive). |
| `ENS_AUDIT_FILE` | (off) | Also append the lines to this file, e.g. `/var/log/ens/readyroom/audit.jsonl`, rotated daily or at 100 MB. The images create `/var/log/ens/readyroom`; the compose files mount the `ens-audit` volume at `/var/log/ens`. |
| `ENS_AUDIT_FILE_KEEP` | `30` | Rotated files kept. |
| `ENS_AUDIT_RETENTION_DAYS` | `0` | Days to keep events; `0` keeps them forever. A daily job deletes older events that every enabled output has delivered, never the newest, and records `audit.pruned`. |
| `ENS_AUDIT_SPLUNK_URL` | (off) | Splunk HTTP Event Collector, e.g. `https://splunk.example:8088`; events are posted to `/services/collector/event` in batches of up to 100. |
| `ENS_AUDIT_SPLUNK_TOKEN` / `ENS_AUDIT_SPLUNK_TOKEN_FILE` | | The HEC token, or a file holding it. Never logged. |
| `ENS_AUDIT_SPLUNK_INDEX` | | Optional index. |
| `ENS_AUDIT_SPLUNK_SOURCETYPE` | `ens:audit` | |
| `ENS_AUDIT_SPLUNK_INSECURE` | `false` | Skip TLS verification (test setups only; warned at startup). Otherwise the JVM's trust store applies. |
| `ENS_AUDIT_SYSLOG_HOST` | (off) | Syslog server: RFC 5424, APP-NAME `readyroom`, MSGID the action, the JSON line as the message. |
| `ENS_AUDIT_SYSLOG_PORT` | `514` / `6514` for tls | |
| `ENS_AUDIT_SYSLOG_PROTOCOL` | `tcp` | `udp`, `tcp` or `tls` (octet-counted framing over tcp and tls). |
| `ENS_AUDIT_SYSLOG_FACILITY` | `local0` | |

Splunk HEC and syslog are delivered by a background forwarder per output that reads the database from a
persisted cursor (`audit_output_state`): requests never wait for them, delivery is at least once (Splunk
can de-duplicate on the event `id`), and failures are retried with exponential backoff up to 5 minutes.
An output failing for more than 15 minutes records `audit.delivery-failed` (severity `alert`) once, and
`audit.delivery-recovered` when it delivers again. With several application instances each forwards the
whole trail, so expect duplicates by `id`.

**Getting it into Splunk**, any one of:

- **HEC:** create an HTTP Event Collector token in Splunk and set `ENS_AUDIT_SPLUNK_URL` and
  `ENS_AUDIT_SPLUNK_TOKEN` (or `_TOKEN_FILE`).
- **Universal Forwarder:** monitor the file (`ENS_AUDIT_FILE`, on the `ens-audit` volume) or the
  container's Docker logs, with `sourcetype=ens:audit`. Lines are pure JSON; with Docker logs, keep only
  lines starting with `{"schema":"ens-audit/1"`.
- **Pull API:** create a token on the audit page and poll
  `GET /api/audit/events?after=<seq>&limit=<1..1000>` with `Authorization: Bearer <token>`. It returns
  NDJSON (one line per event, ascending seq), or `{"events":[...],"next":<seq>}` with
  `Accept: application/json`; pass `next` back as `after`. Tokens (`ensat_...`) are shown once and stored
  only as their SHA-256; they read the audit trail and nothing else, need no session or CSRF token, keep
  working while the application is read-only or unlicensed, and stop at once when revoked. Pulls are not
  themselves audited; the token's last use is shown instead.

---

## Database migrations

ReadyRoom migrates its own schema with [Flyway](https://documentation.red-gate.com/flyway) when it starts,
before it serves anything. The migrations are in
[`src/main/resources/db/migration/`](src/main/resources/db/migration/) and run in version order; each runs
once per database, recorded in the table `flyway_schema_history`. Hibernate only checks the result
(`ddl-auto: validate`): ReadyRoom does not start against a schema that does not match it. Nothing is run by
hand any more, and neither the release package nor the Helm chart carries scripts. How to add a migration:
[`db/README.md`](db/README.md).

**`V1__baseline.sql`** is the whole schema: every table and column, the indexes for the DN and date
lookups, one user record per DN ignoring case, and the audit trail's append-only trigger. It is written to
be safe on any database it meets: on an empty one it creates everything, and on one an earlier release
created it adds only what is missing. That covers what the old psql scripts did
([`db/legacy/`](db/legacy/), kept for reference only): user rows whose DNs differ only by letter case are
merged before the unique index is built (the lowest id is kept, the first non-empty value of each field
wins, the highest role wins, and favourite groups, alternate managers and permissions are combined), and an
index an interrupted script left invalid is rebuilt.

**After the baseline:**

| Migration | What |
|---|---|
| `V2__status_requests.sql` | The `status_requests` table, and `user_event_records.request_id`, the approved request a calendar day comes from (see [Statuses and approvals](#statuses-and-approvals)). Existing rows are untouched. |
| `V3__admin_managed_delegates.sql` | `user_records.alternate_managers` widened to 2000 characters, and `alternate_managers_review_pending`, set on every person who had delegates they chose themselves (see [Delegates](#delegates)). |
| `V4__retention_summaries.sql` | `attendance_summaries` and `attendance_summary_statuses` (monthly roll-ups the retention purge writes), `retention_state` (one row: how far it has purged), and an index on `notification_records.notification_date` |

**Upgrading from a release before Flyway.** Back up the database, then upgrade as usual. On first start
Flyway finds tables but no `flyway_schema_history`, records the existing schema as version 0 (the
`spring.flyway.baseline-*` settings) and runs V1 on it. Where the old scripts had already run, V1 finds
almost everything in place and finishes in seconds; where they had not, it builds the indexes, which on a
large check-in table makes that one start take a few minutes (the Helm chart's startup probe allows 10).
The log shows `Migrating schema "public" to version "1 - baseline"` and, when user rows were merged,
`Merging N duplicate user record(s)`. If a migration fails, ReadyRoom does not start and nothing of that
migration is applied (each runs in one transaction): fix the cause the log names and start it again.

**A separate migration account (optional).** By default ReadyRoom migrates with its own database account
(`DB_USERNAME`). To limit that account to reading and writing rows, give the schema to another one:

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_FLYWAY_USER` | (`DB_USERNAME`) | The account Flyway connects as; it owns the tables it creates |
| `SPRING_FLYWAY_PASSWORD` | (`DB_PASSWORD`) | Its password |

Create both accounts and the database, owned by the migration account, then, connected as the migration
account, before ReadyRoom first starts:

```sql
REVOKE CREATE ON SCHEMA public FROM PUBLIC;            -- PostgreSQL 14 and older; 15+ already does this
GRANT USAGE ON SCHEMA public TO readyroom;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO readyroom;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO readyroom;
```

For an existing database, whose tables the application's account created and owns, first hand them over
(`REASSIGN OWNED BY readyroom TO readyroom_owner;` as a superuser), then run the statements above and
`GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO readyroom;` and
`GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO readyroom;`. The Helm chart's values are
`database.migration.username` and `database.migration.password` (or `.existingSecret`).

## FIPS 140-3

ReadyRoom implements no cryptography of its own. TLS (to browsers, the Windows client, the directory, the
mail server, the database and the audit outputs), certificate and revocation checking, hashing and random
numbers all come from the Java platform's security providers, through the standard JCA/JSSE interfaces.
The image is built on Red Hat UBI 9 OpenJDK 21: on a RHEL host in FIPS mode (`fips-mode-setup --enable`, or
an OpenShift cluster installed with FIPS), Red Hat's OpenJDK switches to its FIPS configuration
automatically, using the host's FIPS-validated NSS and OpenSSL modules and the system crypto policy. Run
ReadyRoom there, with that image, for FIPS 140-3; ReadyRoom does not claim validation of its own.

What ReadyRoom uses: SHA-256 for the audit hash chain, the pull-API token hashes and the session id
fingerprints; SHA-1 only to name a certificate in an OCSP request (the CertID RFC 6960 defines, not a
signature); the platform's `SecureRandom` for session ids, CSRF tokens, CSP nonces and tokens; Ed25519 to
verify the ENS license signature (see below). BouncyCastle is used by the tests only and is not in the
image.

Options that are not FIPS-appropriate, or weaken what FIPS mode protects, and should stay off in a FIPS
deployment:

| Setting | Why |
|---|---|
| `ENS_AUDIT_SPLUNK_INSECURE=true` | Skips the Splunk HEC certificate check |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_TRUST=*` (or a host name) | Trusts the mail server's certificate without checking it |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=false` | Mail, and its credentials, in clear text |
| `ldap://` directory URLs | Passwords and directory data in clear text (warned at startup) |
| `SSL_ENABLED=false` without a TLS-terminating proxy | Everything in clear text |
| `-SkipCertificateCheck` on the Windows client | Trusts any server certificate |
| `ENS_X509_REVOCATION=off`, `ENS_X509_REVOCATION_FAIL=open` | Revoked or unchecked certificates sign in (STIG) |
| Keystores and trust stores (`SERVER_SSL_KEYSTORE`, `SERVER_SSL_TRUSTSTORE`) in a format or with algorithms the FIPS provider does not accept | Load them as PKCS#12 made with FIPS-approved algorithms (AES, SHA-256), or as the provider requires |

### License verification under FIPS

Placeholder: the ENS license library's FIPS test results (Ed25519 signature verification with the platform's
FIPS providers) are to be added here.

## Monitoring and logs

**Health.** `/actuator/health` is open to anyone with the status only; administrators also see each
component. The Kubernetes probes use its groups:

| Path | Includes |
|---|---|
| `/actuator/health/liveness` | ReadyRoom's own state only, so an outage elsewhere never restarts it |
| `/actuator/health/readiness` | Also the database (`db`), the directory (`ldap`) and whether the audit trail can be written (`auditStore`); the mail server is in neither |

**Metrics.** Prometheus metrics are exposed only on a separate management port. Set
`MANAGEMENT_SERVER_PORT` (Spring Boot's own setting; unset by default, and then no metrics are exposed at
all): actuator then moves to that port, plain HTTP, where `/actuator/health` and `/actuator/prometheus`
are open without signing in. Keep that port for the cluster's monitoring: never publish it through an
ingress, route or the host. ReadyRoom's own port then has no actuator endpoints, so point health checks at
the management port (the image's `HEALTHCHECK` does). The Helm chart does all this by default
(`metrics.enabled`, port 9090, with an optional `ServiceMonitor`).

| Metric | Type | |
|---|---|---|
| `ens_license_mode{mode}` | gauge | 1 for the license mode in effect (`full`, `read_only`, `unlicensed`), 0 for the others |
| `ens_license_days_remaining` | gauge | Days until the license (or its grace period) ends; no value for a perpetual license |
| `ens_audit_events_total{category,outcome}` | counter | Audit events stored |
| `ens_audit_forward_failures_total{output}` | counter | Failed deliveries to an audit output (`splunk`, `syslog`) |
| `ens_auth_attempts_total{method,outcome}` | counter | Sign-ins by method (`ldap`, `x509`, `sso`, `api-token`, `session-id`) and outcome (`success`, `failure`), the Windows client's included |
| `ens_readyroom_checkins_total{action}` | counter | The Windows client's events recorded (`in`, `lock`, `unlock`, `out`) |
| `ens_readyroom_people_in` | gauge | People whose latest event today is a check-in or unlock (refreshed at most once a minute) |
| `ens_readyroom_alerts_sent_total` | counter | Absence alert e-mails sent |
| `ens_readyroom_logoffs_refused_total{reason}` | counter | Logoffs refused: `unknown_session`, `rate_limited` |
| `ens_readyroom_requests_total{outcome}` | counter | Status requests `created`, `approved`, `rejected`, `cancelled` |
| `ens_readyroom_retention_purged_total{kind}` | counter | Records the retention purge deleted: `checkin`, `status`, `notification`, `request` |

Spring Boot's own JVM, HTTP, database pool and Tomcat metrics are there too, tagged `application="readyroom"`.

**Logs.** `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` writes each log line as one JSON object (Elastic Common
Schema; `logstash` and `gelf` also work) for a log collector; unset, lines are plain text. The Helm chart's
`logFormat` is `json` by default. The audit lines on stdout (`ENS_AUDIT_STDOUT`) are raw `ens-audit/1`
JSON either way.

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

## Development

**For contributors only.** Nothing in this section is a way to install ReadyRoom for a customer: they
install from the release package or the Helm chart (see [Install](#install)). Building from source,
`./gradlew bootRun`, the `local` profile, `docker build` and the compose stacks in the source tree are
for developing and testing ReadyRoom.

### Running locally

#### 1. Backing services

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

#### 2. Seed mock data

```bash
cd test && ./seed-mock-data.sh
```

Adds mock users and groups to LDAP plus historical, current and future
check-in/out records. It talks to the containers via `docker exec`, so no local
`psql` or `ldap-utils` needed, and it is safe to re-run.

#### 3. Run the app

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

Then open <http://localhost:8181>. Requires a JDK 21 on `JAVA_HOME`.

It needs an ENS license, and a writable place for it: set `ENS_LICENSE_STATE_DIR` and
`ENS_LICENSE_FILE` (for example to `$PWD/license-state` and `$PWD/license-state/license.lic`), then
sign in as an administrator and upload the license at `/app/admin/license`. See
[Licensing](#licensing).

The `local` profile is what points the app at `localhost` and the published
ports. The defaults in `application.yml` name the compose services (`postgres`,
`openldap`), which only resolve from inside the compose network — see
[Configuration](#configuration).

#### Everything in containers instead

```bash
docker compose up --build
```

`docker-compose.yml` in the repository root builds the app from source and runs
it alongside its own PostgreSQL and OpenLDAP. No profile and no overrides: the
packaged defaults already name those services. It reuses the same container
names as `test/docker-compose.yml`, so do not run both at once.

### Product page

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
| `APP_URL` | `http://$host:8181/` | Where `/launch` redirects. `$host` is whatever hostname the visitor used, so the default reaches the app on the same host. Set a full URL if the app is reached another way. |

The page loads nothing from other origins and runs no scripts, which lets nginx
send a strict Content-Security-Policy; keep new markup free of inline `style`
attributes and `<script>` tags. `/healthz` backs the container health check. The
logo and favicon in `product-site/site/assets/` are copies of the app's, so
update both when the branding changes.

### Tests

```bash
./gradlew test
```

JUnit 5 + Mockito, with `@WebMvcTest` slices for the controllers. LDAP code
runs against an in-memory UnboundID directory. Tests of the Flyway migrations, the
audit store and concurrent writes run against PostgreSQL in a container (Testcontainers),
on a schema Flyway built, and are skipped when Docker is not available. The other
repository tests use H2 with the schema built from the entities (`create-drop`, Flyway
off): V1 uses PostgreSQL-only SQL (PL/pgSQL blocks) and H2 cannot run it. Windows sign-in is tested with real
Kerberos tickets from an in-process KDC (Apache Kerby), no domain needed.

Licensing is tested with the ENS portal's published TEST key, which no build trusts: the test
configuration `license/TestLicensing` supplies a `LicenseManager` that trusts it (over a temporary
directory, with a full license installed) in place of the production one, and `TestLicenses` signs
`ens-license/1` files with it on the fly. The portal's own fixtures, signed by its TypeScript code,
are in `src/test/resources/ens-license-fixtures` for the ported library test.

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

### The demo stack: one command

The demo needs a license like any other deployment; without one it shows only the license screen.
Download an evaluation license for ReadyRoom from the ENS portal and save it as
`./license/license.lic` before `up`: the `license-init` service copies it into the demo's license
volume. The demo is read-only, so the license screen's upload is refused there; to change the
license, replace the file and run `up` again. No license is committed to this repository. See
[Licensing](#licensing).

`docker-compose.demo.yml` brings up the whole thing and populates it, with nothing to configure and
the credentials waiting on the login page:

```bash
docker compose -f docker-compose.demo.yml -p readyroom-demo up --build
```

**Nothing is published to the host.** The application listens on 8181 inside the demo network only.
The demo publishes working credentials, so binding it to the host by default would put everything
those accounts can see on the machine's interfaces for anything that can reach them — exposing it
should be a decision, not a default.

To reach it, put something in front of it on the same network, or add a small override file:

```yaml
# demo-ports.yml
services:
  app:
    ports: ["8181:8181"]
```

```bash
docker compose -f docker-compose.demo.yml -f demo-ports.yml -p readyroom-demo up --build
```

Then <http://localhost:8181>. Bind to a single interface (`"127.0.0.1:8181:8181"`) if the machine is
reachable by others.

| Service | What it does |
|---|---|
| `postgres`, `openldap` | The backing services, on their own volumes and network, unpublished |
| `app` | Built from source, running with the `demo` profile |
| `demo-seed-ldap` | Loads the mock users and groups into the directory, then exits |
| `demo-seed-db` | Loads the check-in/out history and the manager role once the tables exist, then exits |

The seeders are what make one `up` enough. One waits for the directory and loads the users and
groups; the other **waits for the application to create its tables** — Flyway does that on first
start, so there is nothing to insert into before then — and only then writes the records. No second
command, and re-running re-seeds cleanly: the LDAP load tolerates entries that already exist and the
SQL deletes its own previous rows.

Both run `test/seed-mock-data.sh` in a **network mode** (`SEED_TRANSPORT=network`): the same script
and the same data as the host path, but reaching the services over the compose network rather than
with `docker exec`, which a container has no socket for. They are two services because
`SEED_ONLY=ldap` needs an image with `ldapadd` and `SEED_ONLY=db` needs one with `psql`. Both are
built from `test/Dockerfile.seed`, which does nothing but copy the script onto one of those two
bases — chosen per service with the `BASE_IMAGE` build argument — so nothing is installed and
nothing beyond the images the stack already pulls is needed.

The script is **copied into the image rather than bind-mounted**, deliberately. A single-file bind
mount whose source does not resolve does not fail: Docker creates a *directory* at the target, and
the container dies with `/seed/seed-mock-data.sh: Is a directory` and exit 126, which says nothing
about the actual problem — and it creates that directory on the host too, so the mistake outlives
the run. Copied in, the path either exists in the image or the build fails saying so.

Use `-p` as shown. Without it compose derives the project name from the directory and this stack
would share its network and volumes with `docker-compose.yml`.

If the application comes up empty, the seeder is what to look at. It verifies its own work — it
counts the users in the directory and the rows in the database, and stops with the reason rather
than reporting success over an empty directory — but a plain `up` does not make that obvious,
because the other services carry on:

```bash
docker compose -f docker-compose.demo.yml -p readyroom-demo logs demo-seed-ldap demo-seed-db
```

> **Not a production configuration.** The credentials are published on the login page, so everything
> those accounts can see is readable by anyone who can reach the port. The mock fixture is the only
> thing that belongs in it.

#### Running the demo without containers

The `demo` profile turns demo mode on and carries the account list, so it is one flag:

```bash
cd test && docker compose up -d && ./seed-mock-data.sh   # data first
mkdir -p license-state && cp ~/Downloads/readyroom-evaluation.lic license-state/license.lic   # the license
ENS_LICENSE_STATE_DIR=$PWD/license-state ENS_LICENSE_FILE=$PWD/license-state/license.lic \
    ./gradlew bootRun --args='--spring.profiles.active=demo'
```

Seed the data first: the accounts on the tile are the seeded ones, and without them the login page
offers credentials that cannot sign in. The seed also grants the manager role, so run it before
judging what each role can see.

For the containerised stack, uncomment `SPRING_PROFILES_ACTIVE: demo` in the root
`docker-compose.yml`, or use `docker-compose.demo.yml`, which sets it already.

### Building the image

CI builds the image customers run (`ghcr.io/winllc/in-n-out-work`, also in the release bundle's
`images/`) from the root `Dockerfile`, which builds from source (Temurin 21 JDK) and runs on Red Hat UBI 9 OpenJDK 21 as the
non-root user 185 (group 0, so OpenShift's arbitrary UIDs work), on port 8181, with a health check on
`/actuator/health` and `JAVA_OPTS` defaulting to `-XX:MaxRAMPercentage=75.0`.

```bash
docker build -t readyroom:latest .

docker run -d --name readyroom -p 8181:8181 \
    -e DB_HOST=db.internal -e DB_PASSWORD=... \
    -e LDAP_HOST=ldap.internal -e LDAP_PASSWORD=... \
    -v readyroom-license:/var/lib/ens/readyroom \
    readyroom:latest
```

`/var/lib/ens/readyroom` (declared a `VOLUME`, owned by user 185 and group 0 with group rwx for
OpenShift) holds the license and the licensing state; keep it on a persistent, writable volume.
Start it without a license, sign in as an administrator and upload one at `/app/admin/license`, or
copy one in: `docker cp license.lic readyroom:/var/lib/ens/readyroom/license.lic` (it must be
readable by user 185). Never bake a license into an image.
The base images are pinned by digest (the tag kept for reading; Dependabot proposes new digests) and can
be swapped for a mirror with `--build-arg BUILD_IMAGE=...` and `--build-arg RUNTIME_IMAGE=...`. The
runtime stage applies the operating system's security updates (`microdnf update`) at build time.

The health check reports what actuator reports, which includes the mail server: if the SMTP host in
`spring.mail` cannot be reached the container shows as unhealthy. Point it at a reachable server, or
set `MANAGEMENT_HEALTH_MAIL_ENABLED=false`.

Both compose files keep the license in a named volume (`ens-license`, `demo_ens_license` in the demo)
at `/var/lib/ens/readyroom`. A `license-init` service copies `./license/license.lic` into it before the
app starts, when that file is newer than the one in the volume; or upload the license on the license
screen. `license/` and `*.lic` are git-ignored. The compose stacks build from `test/Dockerfile`, the
same build without the production defaults.

---

## Releases

Push a tag `vX.Y.Z` (or `vX.Y.Z-rc.1` for a pre-release):

```bash
git tag v1.2.3 && git push origin v1.2.3
```

The `image` job in `.github/workflows/ci.yml` builds and smoke-tests the image as on any push,
publishes it as `ghcr.io/winllc/in-n-out-work:1.2.3` (and `1.2`), and then, from that same image,
builds the customer release bundle with `scripts/release-bundle.sh`:

| File | What |
|---|---|
| `readyroom-1.2.3.tar.gz` | Unpacks to `readyroom-1.2.3/`: `README.md`, `install.sh`, `upgrade.sh`, `backup.sh`, `restore.sh`, a production `docker-compose.yml` (ReadyRoom and PostgreSQL 16, the customer's LDAP and SMTP from `.env`) with optional TLS and Windows sign-in overrides, `env.template`, `license/`, the Windows client, and `images/readyroom-images-1.2.3.tar` (`docker save` of both images, for air-gapped sites). Sources in `deploy/bundle/`. |
| `readyroom-helm-1.2.3.tgz` | The Helm chart (`deploy/helm/readyroom`), packaged as version 1.2.3 and also pushed to `oci://ghcr.io/winllc/charts/readyroom` |
| `sbom-in-n-out-work.spdx.json`, `sbom-postgres.spdx.json` | SPDX SBOMs of the two images |
| `SHA256SUMS` | Checksums of the files above |
| `SHA256SUMS.asc` | Detached GPG signature of `SHA256SUMS`, when a signing key is configured |

They are uploaded to `s3://<bucket>/readyroom/1.2.3/`, and `readyroom/latest.txt` is set to `1.2.3`
(not for pre-releases), and kept as the workflow artifact `release-readyroom-1.2.3` for 30 days.
Every pull request builds the bundle and packages the chart as a dry run (`0.0.0-pr<number>`), kept
as an artifact for 3 days and never uploaded or pushed. The `helm` job lints the chart and validates
what it renders (kubeconform, Kubernetes 1.29) with each of `deploy/helm/readyroom/ci/*.yaml` on every
push and pull request. Signing and upload are skipped, with the reason in the job summary, until
these are configured (Settings > Secrets and variables > Actions):

| Name | Kind | Purpose |
|---|---|---|
| `RELEASE_GPG_PRIVATE_KEY` | secret | ASCII-armored private key that signs `SHA256SUMS` |
| `RELEASE_GPG_PASSPHRASE` | secret | Its passphrase, if it has one |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | secrets | Credentials that can write to the bucket |
| `RELEASE_S3_BUCKET` | variable | The bucket |
| `RELEASE_S3_ENDPOINT` | variable | Endpoint URL for Cloudflare R2 or MinIO; unset for AWS S3 |
| `RELEASE_S3_REGION` | variable | Region; default `us-east-1` (`auto` for R2) |

To build a bundle locally from an image you have (needs Docker; `syft` for the SBOMs, or `SKIP_SBOM=1`):

```bash
docker tag readyroom:latest ghcr.io/winllc/in-n-out-work:1.2.3
scripts/release-bundle.sh 1.2.3 ghcr.io/winllc/in-n-out-work:1.2.3    # output in dist/release/
```

**Verifying a download.** Customers check the files they downloaded, in the directory holding them,
with the ENS Solutions release public key imported (`gpg --import ens-release-key.asc`):

```bash
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum -c SHA256SUMS --ignore-missing
```

**Verifying the image and the chart.** On a release tag CI signs the published image and Helm chart with
cosign, keyless (Sigstore, the workflow's GitHub OIDC identity), by digest, and attaches the image's SPDX
SBOM as a signed attestation:

```bash
cosign verify ghcr.io/winllc/in-n-out-work@<digest> \
  --certificate-identity-regexp 'https://github.com/winllc/in-n-out-work/.github/workflows/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
cosign verify-attestation --type spdxjson ghcr.io/winllc/in-n-out-work@<digest> \
  --certificate-identity-regexp 'https://github.com/winllc/in-n-out-work/.github/workflows/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
cosign verify ghcr.io/winllc/charts/readyroom@<digest> \
  --certificate-identity-regexp 'https://github.com/winllc/in-n-out-work/.github/workflows/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

(`docker buildx imagetools inspect ghcr.io/winllc/in-n-out-work:1.2.3` shows a tag's digest.)

**Supply chain.** Every GitHub Action is pinned to a commit SHA and every base image to a digest;
Dependabot (`.github/dependabot.yml`) proposes weekly updates for Gradle, the Dockerfiles and the actions,
minor and patch versions grouped. CodeQL (`.github/workflows/codeql.yml`) analyses the Java and JavaScript on
every push, pull request and weekly. The `image` job scans the built image with Trivy (pinned by version
and checksum) and fails on any HIGH or CRITICAL vulnerability that has a fix; accepted risks go in
`.trivyignore`, each with its reason and a review date. Security fixes ahead of Spring Boot are pinned in
`build.gradle` (`ext['tomcat.version']`, `ext['jackson-bom.version']`) until a Boot release includes them.
ReadyRoom is proprietary to ENS Solutions (`LICENSE`, `LicenseRef-ENS-Commercial` in the image labels and the
chart's annotations).

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
  license/     ENS license wiring: features, gate, license screen, job guard, size limit
  audit/       The audit trail (ens-audit/1): store, hash chain, outputs, audit log page, pull API
src/main/java/com/enssolutions/license/  The ENS portal's license verifier, copied unchanged
Dockerfile     Production image, built from source; the license lives in the /var/lib/ens volume
docker-compose.yml  The whole stack: PostgreSQL, OpenLDAP and the app from source
docker-compose.demo.yml  Self-seeding demo of the whole application, no sign-in
powershell/    Windows client + scheduled task definitions
product-site/  Product page: static site, nginx config and container
src/main/resources/db/migration/  Flyway migrations, run at startup (see db/README.md)
db/legacy/     The psql scripts used before Flyway, for reference; db/perf/ the volume seed
deploy/bundle/ The customer release bundle: production compose, env template, install/upgrade
deploy/helm/readyroom/  The Helm chart (published to oci://ghcr.io/winllc/charts/readyroom)
scripts/release-bundle.sh  Builds the release bundle (see Releases)
test/          Backing services alone, the app's Dockerfile and mounted config,
               seed script, local CA
```

---

## A note on credentials

No certificate authority keys or keystores are kept in this repository. For local TLS and
certificate sign-in, `test/dev-ca/make-dev-certs.sh` creates a CA of your machine's own (see
`test/dev-ca/README.md`). The `step-ca` service in `test/docker-compose.yml` initialises a fresh
CA in its own volume. Supply production settings through environment variables or an external
config, rather than editing `application.yml`.

The repository used to contain a shared development CA, "WINLLC DEV CA", including its private
keys. They have been removed from its history, but they were public, so **treat that CA as
compromised**: remove it from any trust store that has it.
