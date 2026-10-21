# ReadyRoom Helm chart

Installs ReadyRoom, ENS Solutions' workforce check-in/out and attendance server, on Kubernetes 1.25 or
later (OpenShift 4 included). ReadyRoom needs your PostgreSQL server, your LDAP directory or Active
Directory, an SMTP server for absence e-mails, and an ENS Solutions license.

Full installation guide: https://winllc.github.io/ens-apps-documentation/readyroom/install/

## Install

The chart is published to `oci://ghcr.io/winllc/charts/readyroom`, with the same version as the
ReadyRoom release it installs:

```bash
helm install readyroom oci://ghcr.io/winllc/charts/readyroom --version 1.2.3 \
    --namespace readyroom --create-namespace -f my-values.yaml
```

Each release download also has the chart as `readyroom-helm-<version>.tgz`, covered by `SHA256SUMS`
and its signature: `helm install readyroom ./readyroom-helm-1.2.3.tgz -f my-values.yaml`.

What it creates:

| Resource | What |
|---|---|
| Deployment | ReadyRoom, `replicaCount` replicas (1), port 8181; `RollingUpdate`, or `Recreate` while the license is on a ReadWriteOnce volume. A `license-init` init container when a license is supplied and the license volume is on |
| PodDisruptionBudget | Only with `replicaCount` above 1 |
| ConfigMap | Every non-secret setting, as ReadyRoom's environment. A change rolls the pod |
| Secret | Inline passwords (database, LDAP, SMTP, keystores, Splunk token), and the license file from `license.file` |
| PersistentVolumeClaim | `<release>-readyroom-license` at `/var/lib/ens`: an earlier release's license state, imported into the database on the first start (`persistence.license`) |
| Job | `<release>-readyroom-migrate-<revision>`: runs the database scripts once ReadyRoom has created its tables |
| Service, Ingress, Route | How ReadyRoom is reached |
| StatefulSet | Only with `database.embedded.enabled`: PostgreSQL 16 for evaluation (no backups) |
| NetworkPolicy | Only with `networkPolicy.enabled` |
| Pod (test) | `helm test`: calls the health endpoints and the sign-in page |

## Examples

### Minimal

```yaml
# my-values.yaml
publicUrl: https://readyroom.example.com
database:
  host: postgres.example.com
  password: change-me             # or database.existingSecret
ldap:
  host: dc1.example.com           # ldaps://dc1.example.com:636 (ldap.scheme: ldaps is the default)
  bindDn: CN=readyroom-svc,OU=Service Accounts,DC=example,DC=com
  bindPassword: change-me
  userBaseDn: DC=example,DC=com
  userFilter: (&(objectCategory=person)(objectClass=user))   # Active Directory
  groupBaseDns:
    - OU=Groups,DC=example,DC=com
superUserDns:
  - CN=Jane Admin,OU=Users,DC=example,DC=com
smtp:
  host: smtp.example.com
  username: readyroom
  password: change-me
  from: readyroom@example.com
```

Write list entries that contain commas (every DN) one per line as above: in the flow form
`[CN=a,OU=b]` YAML splits a DN at its commas.

### Existing secrets

Keep every secret out of the values file:

```bash
kubectl -n readyroom create secret generic readyroom-secrets \
    --from-literal=db-password=... --from-literal=ldap-password=... --from-literal=smtp-password=...
```

```yaml
database:
  existingSecret: readyroom-secrets
  existingSecretKey: db-password
ldap:
  existingSecret: readyroom-secrets
  existingSecretKey: ldap-password
smtp:
  existingSecret: readyroom-secrets
  existingSecretKey: smtp-password
```

The same `existingSecret` / `existingSecretKey` pair exists for `database.migration`, `tls.keystore`,
`tls.truststore` and `audit.splunk`.

### License

Optional at install time. Either upload the license on the license screen afterwards, or supply it:

```bash
helm install readyroom oci://ghcr.io/winllc/charts/readyroom --version 1.2.3 -f my-values.yaml \
    --set-file license.file=./license.lic
# or: kubectl create secret generic readyroom-license --from-file=license.lic
#     --set license.existingSecret=readyroom-license
```

It is also passed as `ENS_LICENSE`, which ReadyRoom installs (in its database) **only when no usable license
is installed yet, or when it is a newer revision** of the installed one. A license uploaded later on the
license screen (`/app/admin/license`) is never overwritten by `helm upgrade`. To replace a license, upload it
on the license screen. (With the license volume on, the `license-init` init container also copies it there,
for releases before the license moved to the database.)

The license screen shows the installation id (for example `RR-7F3K-29QD`), which you enter on the ENS
licensing portal to bind a license to this installation. ReadyRoom keeps it, the license and the licensing
state in its database, so **back up the database**. The license PVC (`persistence.license`) is only read on
the first start of a release that keeps them in the database: it imports what an earlier release kept there.
After that upgrade has started once, set `persistence.license.enabled=false` (needed for several replicas,
below). Without the volume the chart sets `ENS_LICENSE_EXPECT_INSTALLATION_ID=true`: ReadyRoom then never
makes up an installation id when its database has none, so a fresh or rebuilt database behind an existing
installation cannot silently break a bound license. A new installation either starts once with the volume on,
or sets `license.newInstallationId: true` for its first start (honoured only while the database holds no
license; unset it afterwards).

### Several replicas

```yaml
replicaCount: 2
persistence:
  license:
    enabled: false      # after the first start of this release, so the license volume was imported
    imported: true      # says so; without it the chart refuses enabled=false while the claim exists
```

The license claim carries `helm.sh/resource-policy: keep` and `argocd.argoproj.io/sync-options: Prune=false`, so
turning the volume off (or `helm uninstall`) leaves it in place; delete it yourself once you no longer want
it. An upgrade straight from an earlier release to `enabled: false` is refused while the claim exists and
`imported` is not set (the chart looks the claim up), so the installation id cannot be skipped.

Scheduled jobs run on one replica at a time (database locks), sessions and the license are in the database,
and with more than one replica the chart adds a PodDisruptionBudget (`podDisruptionBudget.minAvailable`, 1)
and prefers to put the replicas on different nodes. The strategy is `RollingUpdate` (`maxSurge` 1,
`maxUnavailable` 0): a new pod is ready before an old one stops, so the Windows client's events are never
refused during an upgrade. Each pod leaves the Service before it stops (`preStopSleepSeconds`, 10), then
finishes requests and any running job (`terminationGracePeriodSeconds`, 60).

While the license is on a ReadWriteOnce volume the chart refuses `replicaCount` above 1 (two pods cannot
share it) and uses `Recreate` (a new pod on another node could not attach it), so an upgrade is a short
outage; ReadyRoom then holds back absence alerts for people who may have checked in during it (see
"Several replicas, upgrades and outages" in the ReadyRoom README). `audit.file.persistence` is refused with
several replicas too (one file); forward the audit trail to syslog or Splunk instead.

### Single sign-on

OpenID Connect (Entra ID) and SAML (metadata from a ConfigMap), as in `ci/sso-values.yaml`; the ReadyRoom
README's "Single sign-on" section has the settings for Entra ID, Okta and ADFS:

```yaml
sso:
  roleMapping: "<Entra group object id>=ADMIN"
  oidc:
    enabled: true
    issuerUri: https://login.microsoftonline.com/<tenant id>/v2.0
    clientId: <application id>
    existingSecret: readyroom-oidc          # key oidc-client-secret, or existingSecretKey
    userFilter: "(userPrincipalName={upn})"   # the upn optional claim; or (msDS-ExternalDirectoryObjectId=User_{oid})
  saml:
    enabled: true
    idpMetadataUrl: https://login.microsoftonline.com/<tenant id>/federationmetadata/2007-06/federationmetadata.xml?appid=<app id>
    userFilter: "(userPrincipalName={http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn})"
```

Register `<publicUrl>/login/oauth2/code/oidc` (OIDC) and `<publicUrl>/saml2/service-provider-metadata/saml`
(SAML metadata; assertion consumer service `<publicUrl>/login/saml2/sso/saml`) at the identity provider.
SAML needs HTTPS end to end as the browser sees it. With `networkPolicy.enabled`, port 443 is opened for the
identity provider.

### Ingress with TLS (browsers)

TLS ends at the ingress; browsers sign in with their directory username and password.

```yaml
publicUrl: https://readyroom.example.com
ingress:
  enabled: true
  className: nginx
  hosts:
    - host: readyroom.example.com
      paths:
        - path: /
          pathType: Prefix
  tls:
    - secretName: readyroom-ingress-tls
      hosts: [readyroom.example.com]
```

### Client certificates and the Windows client (mTLS passthrough)

Client-certificate (X.509) sign-in, used by browsers with a certificate and by the Windows client's
check-in, lock and unlock calls, needs ReadyRoom itself to terminate TLS. ReadyRoom does not accept a
client certificate forwarded in a header, so an edge-terminating ingress cannot pass one on: use TLS
passthrough or a LoadBalancer Service.

```bash
kubectl -n readyroom create secret generic readyroom-tls \
    --from-file=keystore.p12 --from-file=truststore.p12 \
    --from-literal=keystore-password=... --from-literal=truststore-password=...
```

```yaml
publicUrl: https://readyroom.example.com
tls:
  enabled: true
  clientAuth: want          # keep "want": the probes have no certificate
  keystore:
    secretName: readyroom-tls
    existingSecret: readyroom-tls
  truststore:
    secretName: readyroom-tls
    existingSecret: readyroom-tls
```

Then one of:

```yaml
# a) ingress-nginx with TLS passthrough (the controller must run with --enable-ssl-passthrough)
ingress:
  enabled: true
  className: nginx
  annotations:
    nginx.ingress.kubernetes.io/ssl-passthrough: "true"
    nginx.ingress.kubernetes.io/backend-protocol: HTTPS
  hosts:
    - host: readyroom.example.com
      paths: [{path: /, pathType: Prefix}]

# b) a LoadBalancer Service of its own: https://<address>:8181
service:
  type: LoadBalancer
  externalTrafficPolicy: Local
```

Both stores are required: the image contains no keystore or truststore, and the render fails without
them (a truststore can be left out only with `clientAuth: none`, which turns certificate sign-in off).
Put only the CAs that issue your users' certificates in the truststore.

The keystore's certificate must name the host the Windows client's `BaseUrl` uses, for example
`https://readyroom.example.com:8181/api/check` with the LoadBalancer, or
`https://readyroom.example.com/api/check` through the ingress.

### Windows (Kerberos) sign-in

```bash
kubectl -n readyroom create secret generic readyroom-keytab --from-file=http.keytab
```

```yaml
windowsAuth:
  enabled: true
  servicePrincipal: HTTP/readyroom.example.com@EXAMPLE.COM
  keytab:
    secretName: readyroom-keytab
  krb5Conf: |               # optional; checking a ticket needs only the keytab
    [libdefaults]
      default_realm = EXAMPLE.COM
```

### OpenShift

```yaml
openshift:
  enabled: true             # the restricted SCC assigns the user, group and fsGroup
route:
  enabled: true
  host: readyroom.apps.example.com
  tls:
    termination: edge       # passthrough (with tls.enabled) for client certificates
```

### Air-gapped

Mirror the images and point the chart at the mirror:

| Image | Mirrored as |
|---|---|
| `ghcr.io/winllc/in-n-out-work:<version>` | `<registry>/winllc/in-n-out-work:<version>` |
| `postgres:16` (embedded database) | `<registry>/postgres:16` |
| `busybox:1.36.1` (license init container) | `<registry>/busybox:1.36.1` |

The release bundle's `images/` archive holds the ReadyRoom and PostgreSQL images (`docker load`, then
tag and push). Copy the chart from the release download (`readyroom-helm-<version>.tgz`) or
`helm pull oci://ghcr.io/winllc/charts/readyroom --version <version>` on a connected machine.

```yaml
global:
  imageRegistry: registry.example.internal:5000
imagePullSecrets:
  - name: mirror-pull
```

### Evaluation with the embedded database

```yaml
database:
  embedded:
    enabled: true           # EVALUATION ONLY, NO BACKUPS
```

The password is generated and kept in the release's Secret unless you set `database.password`.

### Upgrade

```bash
helm upgrade readyroom oci://ghcr.io/winllc/charts/readyroom --version 1.2.4 -n readyroom -f my-values.yaml
```

Back up the database first (see [Backups](#backups)). The license PVC and the database are kept. ReadyRoom
migrates its schema itself when the new pod starts (Flyway, `src/main/resources/db/migration`), before it
serves anything; the pod is ready once that is done. The first upgrade to a release with Flyway records the
existing schema as version 0 and brings it up to date (it builds any index the old migration Job had not, so
that one start takes longer on a large database). There is no migration Job any more; an old one from a
previous release is removed by the upgrade.

Upgrading to the release with sessions, job locks and the license in the database (Flyway V6 to V8) needs
nothing by hand: keep your values, and the first start imports the license volume. Everyone signs in again
once. To go to several replicas afterwards, see [Several replicas](#several-replicas); until then the chart
keeps `Recreate`, and absence alerts wait for people whose check-in may have been sent while the pod was
restarting.

Upgrading to the release with the public API, webhooks and scheduled reports (Flyway V9 and V10) needs nothing by
hand either. The
chart adds a generated `webhook-secret-key` to its Secret (or uses `webhooks.secretKey`), and with
`networkPolicy.enabled` opens egress to port 443 for webhooks (`webhooks.ports: []` closes it).

### Backups

With `backup.enabled`, a CronJob runs `pg_dump` (custom format, with the database image so the version
matches) on `backup.schedule` into a PVC (`backup.persistence`), and deletes dumps older than
`backup.retentionDays`. It works with your PostgreSQL or the embedded one, but it is no substitute for your
database's own backups and point-in-time recovery. The license PVC (the installation id) is not in it: snapshot
that volume too. Copy the dumps off the cluster as well.

```yaml
backup:
  enabled: true
  schedule: "17 2 * * *"     # timeZone: America/New_York
  retentionDays: 14
  persistence:
    size: 10Gi
```

To back up now: `kubectl -n readyroom create job --from=cronjob/readyroom-backup readyroom-backup-now`.

To restore a dump (everything recorded since it is lost):

```bash
kubectl -n readyroom scale deploy/readyroom --replicas=0
kubectl -n readyroom run readyroom-restore --rm -it --restart=Never --image=postgres:16 \
  --overrides='{"spec":{"volumes":[{"name":"b","persistentVolumeClaim":{"claimName":"readyroom-backup"}}],
    "containers":[{"name":"readyroom-restore","image":"postgres:16","stdin":true,"tty":true,
    "command":["bash"],"volumeMounts":[{"name":"b","mountPath":"/backup"}]}]}}'
# inside it:
ls /backup
PGPASSWORD=... pg_restore --clean --if-exists --no-owner --single-transaction --exit-on-error \
  -h <database host> -U readyroom -d readyroom /backup/readyroom-<stamp>.dump
exit
kubectl -n readyroom scale deploy/readyroom --replicas=1
```

ReadyRoom migrates an older dump's schema forward when it starts; restore into the same or a newer release.

### Uninstall

```bash
helm uninstall readyroom -n readyroom
```

This deletes the license PVC with the release, unless it is an `existingClaim`; the installation id
goes with it. The embedded database's PVC (`data-<release>-readyroom-postgresql-0`) is kept until you
delete it.

## Values

| Key | Default | Description |
|---|---|---|
| `global.imageRegistry` | `""` | Replaces the registry of every image (air-gapped mirror) |
| `image.repository` | `ghcr.io/winllc/in-n-out-work` | ReadyRoom image |
| `image.tag` | `""` | Empty: the chart's appVersion |
| `image.pullPolicy` | `IfNotPresent` | |
| `imagePullSecrets` | `[]` | Pull secrets for every pod |
| `nameOverride`, `fullnameOverride` | `""` | |
| `replicaCount` | `1` | More than 1 needs `persistence.license.enabled=false` (or ReadWriteMany); see Several replicas |
| `strategy` | `{}` | Empty: `RollingUpdate` (maxSurge 1, maxUnavailable 0), or `Recreate` while the license is on a ReadWriteOnce volume |
| `podDisruptionBudget.minAvailable` | `1` | With more than one replica |
| `preStopSleepSeconds`, `terminationGracePeriodSeconds` | `10`, `60` | Leaving the Service, then finishing requests and running jobs |
| `revisionHistoryLimit` | `3` | |
| `serviceAccount.create`, `.name`, `.annotations` | `true`, `""`, `{}` | The token is never mounted |
| `podAnnotations`, `podLabels` | `{}` | |
| `resources` | requests 250m / 768Mi, limit 1536Mi | |
| `nodeSelector`, `tolerations`, `affinity`, `topologySpreadConstraints`, `priorityClassName` | empty | |
| `podSecurityContext` | non-root 185:0, fsGroup 185, RuntimeDefault seccomp | |
| `securityContext` | no privilege escalation, drop ALL, read-only root | `/tmp` and `/var/tmp` are emptyDirs |
| `openshift.enabled` | `false` | Leave the ids out so the restricted SCC assigns them |
| `javaOpts` | `-XX:MaxRAMPercentage=75.0` | `JAVA_OPTS` |
| `publicUrl` | `""` | `APPLICATION_BASE_URL`; empty: from the ingress or route host |
| `bannerText` | `ReadyRoom` | `APPLICATION_BANNERTEXT` |
| `timezone` | `America/New_York` | `SPRING_JACKSON_TIMEZONE`, `APPLICATION_TIMEZONE` |
| `checkOutAfterMinutes` | `240` | `APPLICATION_CHECKOUTAFTERMINUTES` |
| `updateProfileUrl` | `""` | `APPLICATION_UPDATEPROFILEURL` |
| `logLevel` | `info` | `LOGGING_LEVEL_COM_WINLLC_INNOUTWORK` |
| `logFormat` | `json` | `json`: ECS JSON log lines (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`); `text`: plain lines |
| `csp.reportOnly` | `false` | `ENS_CSP_REPORT_ONLY`: report the Content-Security-Policy instead of enforcing it |
| `consentBanner.enabled` | `false` | `ENS_CONSENT_BANNER_ENABLED`: the DoD notice-and-consent banner before any sign-in |
| `consentBanner.title`, `.text` | `""` | `ENS_CONSENT_BANNER_TITLE`, `_TEXT`; empty: the standard DoD title and text |
| `consentBanner.existingConfigMap`, `.existingConfigMapKey` | `""`, `banner.txt` | The text from your ConfigMap (`ENS_CONSENT_BANNER_FILE`); wins over `text` |
| `session.idleTimeout`, `.adminIdleTimeout`, `.absoluteTimeout`, `.maxPerUser` | `30m`, `""`, `12h`, `0` | `ENS_SESSION_*`; STIG suggests `adminIdleTimeout: 15m`, `maxPerUser: 3` |
| `events.maxRangeDays` | `366` | `APPLICATION_EVENTS_MAXRANGEDAYS`: the most days one call may set a status or request on |
| `events.requestStatuses` | `[]` (every status that excuses an absence) | `APPLICATION_EVENTS_REQUESTSTATUSES`: statuses people request and their manager approves |
| `privacy.coworkerVisibility` | `full` | `APPLICATION_PRIVACY_COWORKERVISIBILITY`: `full`, `status` (in or out only) or `none` |
| `retention.eventsDays` | `0` | `APPLICATION_RETENTION_EVENTSDAYS`: days to keep check-ins, statuses, requests and alerts (0: forever; else 31 or more), then summarised by month and deleted |
| `retention.schedule` | `0 17 2 * * *` | `APPLICATION_RETENTION_CRON`: when the purge runs (six-field Spring cron) |
| `reports.siteTimeZones` | `[]` | `APPLICATION_REPORTS_SITETIMEZONES`: `"<location>=<IANA zone>"` per site, for times in reports, exports and the API |
| `reports.maxRangeDays`, `.maxAttachmentBytes`, `.maxRecipients` | `366`, `10485760`, `20` | `APPLICATION_REPORTS_*`: the longest export, and a scheduled report's largest attachment and most recipients |
| `api.maxTokenDays`, `.defaultRatePerMinute`, `.maxRatePerMinute` | `366`, `120`, `1200` | `APPLICATION_API_*`: the longest a public API token may be valid, and its requests a minute (offered, and at most) |
| `webhooks.secretKey.value` / `.existingSecret` / `.existingSecretKey` | generated / `""` / `webhook-secret-key` | `APPLICATION_WEBHOOKS_SECRETKEY`: the key webhook signing secrets are encrypted with (32+ characters, every replica the same). Empty: generated on install and kept across upgrades |
| `webhooks.allowedCidrs` | `[]` | `APPLICATION_WEBHOOKS_ALLOWEDCIDRS`: private or loopback ranges webhooks may still go to (cloud metadata never) |
| `webhooks.ports` | `[443]` | With `networkPolicy.enabled`: ports webhooks may be sent to |
| `superUserDns` | `[]` | `APPLICATION_SUPERUSERDNS_n`: administrators by DN; at least one |
| `database.host` | `""` | Required unless embedded |
| `database.port`, `.name`, `.username` | `5432`, `readyroom`, `readyroom` | |
| `database.password` / `.existingSecret` / `.existingSecretKey` | `""` / `""` / `password` | Required (generated for the embedded database) |
| `database.embedded.enabled` | `false` | PostgreSQL StatefulSet: evaluation only (no backups unless `backup.enabled`) |
| `backup.enabled` | `false` | A CronJob running `pg_dump` into a PVC |
| `backup.schedule`, `.timeZone` | `17 2 * * *`, `""` | When |
| `backup.retentionDays` | `14` | Dumps older than this are deleted |
| `backup.persistence.*` | 10Gi, RWO | `size`, `storageClass`, `accessModes`, `existingClaim` |
| `backup.activeDeadlineSeconds`, `.resources` | `3600`, small | |
| `database.embedded.image.*` | `postgres`, `16` | |
| `database.embedded.persistence.*` | 8Gi, RWO | `size`, `storageClass`, `accessModes`, `existingClaim` |
| `database.embedded.resources` | 100m / 256Mi, limit 1Gi | |
| `database.migration.username` | `""` | `SPRING_FLYWAY_USER`: a separate account that owns the schema and migrates it at startup; empty: `database.username` |
| `database.migration.password` / `.existingSecret` / `.existingSecretKey` | `""` / `""` / `migration-password` | `SPRING_FLYWAY_PASSWORD`; required with `database.migration.username` |
| `ldap.scheme`, `ldap.host`, `ldap.port` | `ldaps`, `""`, `""` (636; 389 for `ldap`) | `SPRING_LDAP_URLS=<scheme>://<host>:<port>`. `ldap` sends sign-in passwords in clear text (warned at startup); `ldaps` with port 389 fails the render |
| `ldap.url` | `""` | `SPRING_LDAP_URLS`, e.g. `ldaps://dc1.example.com:636`; overrides scheme, host and port |
| `ldap.bindDn` | `""` | `LDAP_USERNAME` |
| `ldap.bindPassword` / `.existingSecret` / `.existingSecretKey` | `""` / `""` / `ldap-password` | `LDAP_PASSWORD` |
| `ldap.userBaseDn` | `""` | `APPLICATION_USERBASEDN` |
| `ldap.userFilter` | `""` | `APPLICATION_USERLDAPFILTER`; empty: `(objectclass=inetOrgPerson)` |
| `ldap.groupBaseDns` | `[]` | `APPLICATION_GROUPS_n_GROUPSBASEDN` |
| `ldap.attributes.organization`, `.location`, `.employeeType`, `.managerId`, `.managerLdapId`, `.dutySubOrganization` | `""` | Directory attributes; empty keeps `o`, `l`, `employeeType`, `title`, `street`, `departmentNumber` |
| `orgChart.groupsBaseDn` | `""` | `APPLICATION_DUTYSUBORGGROUPSBASEDN`; empty turns the org chart off |
| `smtp.host`, `smtp.port`, `smtp.username` | `""`, `587`, `""` | `SPRING_MAIL_*` |
| `smtp.password` / `.existingSecret` / `.existingSecretKey` | `""` / `""` / `smtp-password` | `SPRING_MAIL_PASSWORD` |
| `smtp.auth`, `smtp.starttls` | `true`, `true` | SMTP AUTH and STARTTLS |
| `smtp.sslTrust` | `""` | Empty: the mail server's certificate is checked; a host name (or `*`, any) trusts it without checking |
| `smtp.from` | `""` | `APPLICATION_NOTIFICATIONSENDEREMAIL` |
| `smtp.healthCheck` | `true` | `MANAGEMENT_HEALTH_MAIL_ENABLED`; never part of the Kubernetes probes |
| `tls.enabled` | `false` | ReadyRoom terminates TLS itself (`SSL_ENABLED`) |
| `tls.clientAuth` | `want` | `SERVER_SSL_CLIENTAUTH` |
| `tls.keystore.secretName`, `.secretKey`, `.type` | `""`, `keystore.p12`, `PKCS12` | Required with `tls.enabled` |
| `tls.keystore.password` / `.existingSecret` / `.existingSecretKey` | `""` / `""` / `keystore-password` | |
| `tls.truststore.*` | as keystore, `truststore.p12`, `truststore-password` | CAs of client certificates; required with `tls.enabled` unless `tls.clientAuth` is `none` |
| `windowsAuth.enabled` | `false` | Kerberos sign-in for the Windows client |
| `sso.roleMapping`, `sso.clockSkew` | `""`, `60s` | IdP group to role (`group=ADMIN;group=MANAGER`); clock skew allowed |
| `sso.oidc.enabled`, `.issuerUri`, `.clientId`, `.clientSecret` / `.existingSecret`, `.userFilter`, `.groupsClaim`, `.scopes`, `.displayName` | off | OpenID Connect (ENS_SSO_OIDC_*) |
| `sso.saml.enabled`, `.idpMetadataUrl` / `.metadataConfigMap`, `.entityId`, `.userFilter`, `.groupsAttribute`, `.spKeySecret`, `.displayName` | off | SAML 2.0 (ENS_SSO_SAML_*) |
| `windowsAuth.servicePrincipal` | `""` | Required when enabled |
| `windowsAuth.keytab.secretName`, `.secretKey` | `""`, `http.keytab` | Required when enabled; mounted at `/etc/in-n-out/http.keytab` |
| `windowsAuth.krb5Conf`, `.krb5ConfExistingConfigMap`, `.krb5ConfExistingConfigMapKey` | `""`, `""`, `krb5.conf` | Optional `/etc/krb5.conf` |
| `windowsAuth.accountAttribute`, `.stripRealm`, `.allowedRealms` | `sAMAccountName`, `true`, `""` | |
| `audit.stdout` | `true` | `ENS_AUDIT_STDOUT` |
| `audit.file.enabled`, `.path`, `.keep` | `false`, `/var/log/ens/readyroom/audit.jsonl`, `30` | `ENS_AUDIT_FILE`, `ENS_AUDIT_FILE_KEEP` |
| `audit.file.persistence.*` | disabled, 1Gi | A PVC for the audit file (else an emptyDir) |
| `audit.retentionDays` | `0` | `ENS_AUDIT_RETENTION_DAYS` |
| `audit.splunk.url`, `.index`, `.sourcetype`, `.insecure` | `""`, `""`, `ens:audit`, `false` | `ENS_AUDIT_SPLUNK_*` |
| `audit.splunk.token` / `.existingSecret` / `.existingSecretKey` | `""` / `""` / `splunk-token` | `ENS_AUDIT_SPLUNK_TOKEN` |
| `audit.syslog.host`, `.port`, `.protocol`, `.facility` | `""`, `""`, `tcp`, `local0` | `ENS_AUDIT_SYSLOG_*` |
| `license.newInstallationId` | `false` | Accept a new installation id when the database has none and no volume is mounted (`ENS_LICENSE_NEW_INSTALLATION_ID`) |
| `persistence.license.enabled` | `true` | The license PVC at `/var/lib/ens`, imported into the database on the first start; turn off afterwards for several replicas |
| `persistence.license.size`, `.storageClass`, `.accessModes`, `.existingClaim` | `100Mi`, `""`, RWO, `""` | |
| `license.existingSecret` | `""` | Secret with key `license.lic` |
| `license.file` | `""` | The license itself (`--set-file license.file=./license.lic`) |
| `licenseInit.image.*` | `busybox`, `1.36.1` | The license init container |
| `service.type`, `.port` | `ClusterIP`, `8181` | `LoadBalancer` for the Windows client's direct TLS address |
| `service.annotations`, `.loadBalancerIP`, `.loadBalancerSourceRanges`, `.externalTrafficPolicy`, `.nodePort` | empty | |
| `ingress.enabled`, `.className`, `.annotations`, `.hosts`, `.tls` | disabled | |
| `route.enabled`, `.host`, `.annotations` | disabled | OpenShift Route |
| `route.tls.termination`, `.insecureEdgeTerminationPolicy` | `edge`, `Redirect` | `passthrough` / `reencrypt` need `tls.enabled` |
| `networkPolicy.enabled` | `false` | |
| `networkPolicy.ingressFrom` | `[]` | Peers allowed to port 8181; empty: anyone |
| `networkPolicy.egress.allowAll` | `false` | Else DNS, plus the database, LDAP, SMTP and audit ports to `cidrs` |
| `networkPolicy.egress.cidrs`, `.extraPorts` | `[0.0.0.0/0]`, `[]` | |
| `metrics.enabled` | `true` | `MANAGEMENT_SERVER_PORT`: actuator on a port of its own (plain HTTP) with `/actuator/prometheus` and the probes, open without sign-in; Service port `metrics` |
| `metrics.port` | `9090` | |
| `metrics.allowFrom` | `[]` | NetworkPolicy peers admitted to the metrics port (with `networkPolicy.enabled`) |
| `metrics.serviceMonitor.enabled`, `.interval`, `.scrapeTimeout`, `.labels` | `false`, `30s`, `""`, `{}` | A Prometheus Operator `ServiceMonitor` |
| `probes.startup`, `.liveness`, `.readiness` | startup up to 10 min | Actuator `/actuator/health/liveness` and `/readiness`, on the metrics port when `metrics.enabled` |
| `extraEnv`, `extraEnvFrom` | `[]` | Any other ReadyRoom setting |
| `extraVolumes`, `extraVolumeMounts` | `[]` | |
| `tests.enabled` | `true` | The `helm test` pod |

## Development

`ci/*.yaml` are the example values CI lints and validates with:

```bash
for f in deploy/helm/readyroom/ci/*.yaml; do
  helm lint --strict deploy/helm/readyroom -f "$f"
  helm template rr deploy/helm/readyroom -f "$f" | kubeconform -strict -summary -kubernetes-version 1.29.0 -ignore-missing-schemas
done
```

The chart carries no database scripts: ReadyRoom migrates its own schema at startup (see `db/README.md`).
