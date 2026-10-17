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
| Deployment | ReadyRoom, one replica (`Recreate`), port 8181. A `license-init` init container when a license is supplied |
| ConfigMap | Every non-secret setting, as ReadyRoom's environment. A change rolls the pod |
| Secret | Inline passwords (database, LDAP, SMTP, keystores, Splunk token), and the license file from `license.file` |
| PersistentVolumeClaim | `<release>-readyroom-license` at `/var/lib/ens`: the license, the installation id and the licensing state |
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
  host: dc1.example.com
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

The `license-init` init container copies it into the license volume **only when no license is
installed there yet**, as the release bundle's `install.sh` does. A license uploaded later on the
license screen (`/app/admin/license`) is never overwritten by `helm upgrade`. To replace a license,
upload it on the license screen.

The license screen shows the installation id (for example `RR-7F3K-29QD`), which you enter on the ENS
licensing portal to bind a license to this installation. It is kept in the license PVC, so **keep that
PVC**. `helm uninstall` deletes it with the rest of the release; to keep it independent of the release,
create the claim yourself and set `persistence.license.existingClaim`.

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

Back up the database first. The license PVC and the database are kept. ReadyRoom
migrates its schema itself when the new pod starts (Flyway, `src/main/resources/db/migration`), before it
serves anything; the pod is ready once that is done. The first upgrade to a release with Flyway records the
existing schema as version 0 and brings it up to date (it builds any index the old migration Job had not, so
that one start takes longer on a large database). There is no migration Job any more; an old one from a
previous release is removed by the upgrade.

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
| `replicaCount` | `1` | Only 1 is supported: the license volume and the scheduled jobs are single-writer |
| `strategy.type` | `Recreate` | |
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
| `superUserDns` | `[]` | `APPLICATION_SUPERUSERDNS_n`: administrators by DN; at least one |
| `database.host` | `""` | Required unless embedded |
| `database.port`, `.name`, `.username` | `5432`, `readyroom`, `readyroom` | |
| `database.password` / `.existingSecret` / `.existingSecretKey` | `""` / `""` / `password` | Required (generated for the embedded database) |
| `database.embedded.enabled` | `false` | PostgreSQL StatefulSet: evaluation only, no backups |
| `database.embedded.image.*` | `postgres`, `16` | |
| `database.embedded.persistence.*` | 8Gi, RWO | `size`, `storageClass`, `accessModes`, `existingClaim` |
| `database.embedded.resources` | 100m / 256Mi, limit 1Gi | |
| `database.migration.username` | `""` | `SPRING_FLYWAY_USER`: a separate account that owns the schema and migrates it at startup; empty: `database.username` |
| `database.migration.password` / `.existingSecret` / `.existingSecretKey` | `""` / `""` / `migration-password` | `SPRING_FLYWAY_PASSWORD`; required with `database.migration.username` |
| `ldap.host`, `ldap.port` | `""`, `389` | `LDAP_HOST`, `LDAP_PORT` |
| `ldap.url` | `""` | `SPRING_LDAP_URLS`, e.g. `ldaps://dc1.example.com:636`; overrides host and port |
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
| `smtp.sslTrust` | `""` | Trusted mail server certificates; empty keeps `*` |
| `smtp.from` | `""` | `APPLICATION_NOTIFICATIONSENDEREMAIL` |
| `smtp.healthCheck` | `true` | `MANAGEMENT_HEALTH_MAIL_ENABLED`; never part of the Kubernetes probes |
| `tls.enabled` | `false` | ReadyRoom terminates TLS itself (`SSL_ENABLED`) |
| `tls.clientAuth` | `want` | `SERVER_SSL_CLIENTAUTH` |
| `tls.keystore.secretName`, `.secretKey`, `.type` | `""`, `keystore.p12`, `PKCS12` | Required with `tls.enabled` |
| `tls.keystore.password` / `.existingSecret` / `.existingSecretKey` | `""` / `""` / `keystore-password` | |
| `tls.truststore.*` | as keystore, `truststore.p12`, `truststore-password` | CAs of client certificates; required with `tls.enabled` unless `tls.clientAuth` is `none` |
| `windowsAuth.enabled` | `false` | Kerberos sign-in for the Windows client |
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
| `persistence.license.enabled` | `true` | The license PVC at `/var/lib/ens` |
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
| `probes.startup`, `.liveness`, `.readiness` | startup up to 10 min | Actuator `/actuator/health/liveness` and `/readiness` |
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
