{{/* Names */}}
{{- define "readyroom.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "readyroom.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "readyroom.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "readyroom.labels" -}}
helm.sh/chart: {{ include "readyroom.chart" . }}
{{ include "readyroom.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: readyroom
{{- end -}}

{{- define "readyroom.selectorLabels" -}}
app.kubernetes.io/name: {{ include "readyroom.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "readyroom.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "readyroom.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "readyroom.postgresql.fullname" -}}
{{- printf "%s-postgresql" (include "readyroom.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
An image reference. global.imageRegistry, when set, replaces the registry part of the repository
(the first path element when it holds a "." or ":" or is "localhost"), or is put in front of a
repository that has none (postgres, busybox).
Call with: dict "image" <image values> "root" $ "tag" <default tag>
*/}}
{{- define "readyroom.image" -}}
{{- $repo := .image.repository -}}
{{- $registry := "" -}}
{{- if .root.Values.global -}}{{- $registry = .root.Values.global.imageRegistry | default "" -}}{{- end -}}
{{- if $registry -}}
{{- $parts := splitList "/" $repo -}}
{{- $first := first $parts -}}
{{- if and (gt (len $parts) 1) (or (contains "." $first) (contains ":" $first) (eq $first "localhost")) -}}
{{- $repo = join "/" (rest $parts) -}}
{{- end -}}
{{- $repo = printf "%s/%s" (trimSuffix "/" $registry) $repo -}}
{{- end -}}
{{- $tag := .image.tag | default .tag | toString -}}
{{- printf "%s:%s" $repo $tag -}}
{{- end -}}

{{- define "readyroom.appImage" -}}
{{- include "readyroom.image" (dict "image" .Values.image "root" . "tag" .Chart.AppVersion) -}}
{{- end -}}

{{- define "readyroom.dbImage" -}}
{{- include "readyroom.image" (dict "image" .Values.database.embedded.image "root" . "tag" "16") -}}
{{- end -}}

{{/* Pod security context, without the ids under OpenShift. */}}
{{- define "readyroom.podSecurityContext" -}}
{{- $ctx := deepCopy .ctx -}}
{{- if .root.Values.openshift.enabled -}}
{{- $_ := unset $ctx "runAsUser" -}}
{{- $_ := unset $ctx "runAsGroup" -}}
{{- $_ := unset $ctx "fsGroup" -}}
{{- end -}}
{{- toYaml $ctx -}}
{{- end -}}

{{- define "readyroom.containerSecurityContext" -}}
{{- $ctx := deepCopy .Values.securityContext -}}
{{- if .Values.openshift.enabled -}}
{{- $_ := unset $ctx "runAsUser" -}}
{{- $_ := unset $ctx "runAsGroup" -}}
{{- end -}}
{{- toYaml $ctx -}}
{{- end -}}

{{/*
Settings that cannot work as given: fails the render and says what to set. Included by the Deployment.
The image holds no keystore or truststore, and ReadyRoom refuses to start with TLS on without them.
*/}}
{{- define "readyroom.validate" -}}
{{- $v := .Values -}}
{{- if and $v.tls.enabled (not $v.tls.keystore.secretName) -}}
{{- fail "tls.keystore.secretName is required when tls.enabled: a Secret holding your PKCS#12 keystore (the image has none)" -}}
{{- end -}}
{{- if and $v.tls.enabled (ne (toString $v.tls.clientAuth) "none") (not $v.tls.truststore.secretName) -}}
{{- fail (printf "tls.truststore.secretName is required when tls.enabled with clientAuth %s: a Secret holding a PKCS#12 truststore with only the CAs that issue your users' client certificates (the image has none, and ReadyRoom does not fall back to Java's default CAs). Or set tls.clientAuth to none to turn certificate sign-in off" (toString $v.tls.clientAuth)) -}}
{{- end -}}
{{- if and $v.database.migration.username (not (or $v.database.migration.password $v.database.migration.existingSecret)) -}}
{{- fail "database.migration.password or database.migration.existingSecret is required with database.migration.username: the account Flyway migrates the schema as" -}}
{{- end -}}
{{- if eq (toString $v.x509.source) "header" -}}
{{- if not (or $v.x509.proxySecret.value $v.x509.proxySecret.existingSecret) -}}
{{- fail "x509.source header needs x509.proxySecret.value or x509.proxySecret.existingSecret: the secret (32 characters or more) your ingress sends in X-ENS-Proxy-Secret, without which ReadyRoom would take a certificate header from anyone" -}}
{{- end -}}
{{- if and $v.x509.proxySecret.value (lt (len $v.x509.proxySecret.value) 32) -}}
{{- fail "x509.proxySecret.value must be at least 32 characters" -}}
{{- end -}}
{{- if not (or $v.x509.trustedCa.value $v.x509.trustedCa.existingSecret (and $v.tls.enabled $v.tls.truststore.secretName)) -}}
{{- fail "x509.source header needs x509.trustedCa.value or x509.trustedCa.existingSecret: the PEM certificates of the CAs that issue your users' certificates, which forwarded certificates are checked against" -}}
{{- end -}}
{{- end -}}
{{- if and (eq (toString $v.ldap.scheme) "ldaps") (eq (toString $v.ldap.port) "389") (not $v.ldap.url) -}}
{{- fail "ldap.port is 389 with ldap.scheme ldaps (the default since LDAPS became it): set ldap.port to your directory's LDAPS port (636), or ldap.scheme: ldap to keep connecting in clear text" -}}
{{- end -}}
{{- if gt (int $v.replicaCount) 1 -}}
{{- if and $v.persistence.license.enabled (not (has "ReadWriteMany" $v.persistence.license.accessModes)) -}}
{{- fail "replicaCount > 1 needs the license state off the pods' ReadWriteOnce volume: this release keeps it in persistence.license, which two pods cannot share. Keep replicaCount 1 (see \"Several replicas\" in the chart README)" -}}
{{- end -}}
{{- if and $v.audit.file.enabled $v.audit.file.persistence.enabled -}}
{{- fail "replicaCount > 1 cannot share audit.file.persistence (ReadWriteOnce, one file): turn audit.file.persistence off (each pod's file is then an emptyDir) or send the audit trail to syslog/Splunk, which read it from the database" -}}
{{- end -}}
{{- end -}}
{{- if and $v.windowsAuth.enabled (not $v.windowsAuth.keytab.secretName) -}}
{{- fail "windowsAuth.keytab.secretName is required when windowsAuth.enabled: a Secret holding the service principal's keytab" -}}
{{- end -}}
{{- end -}}

{{/* The directory port: ldap.port, or the scheme's own (636 for ldaps, 389 for ldap). */}}
{{- define "readyroom.ldapPort" -}}
{{- $l := .Values.ldap -}}
{{- if $l.port -}}{{ $l.port }}{{- else if eq (toString $l.scheme) "ldap" -}}389{{- else -}}636{{- end -}}
{{- end -}}

{{/* The database host: the embedded StatefulSet's Service, or database.host (required). */}}
{{- define "readyroom.dbHost" -}}
{{- if .Values.database.embedded.enabled -}}
{{- include "readyroom.postgresql.fullname" . -}}
{{- else -}}
{{- required "database.host is required: your PostgreSQL server (or set database.embedded.enabled=true for an evaluation)" .Values.database.host -}}
{{- end -}}
{{- end -}}

{{/*
Where a secret value comes from: {name, key} of the user's existingSecret, or of the chart's Secret
when an inline value is set; empty when neither is.
Call with: dict "root" $ "value" <inline> "existingSecret" <name> "existingSecretKey" <key> "key" <chart secret key>
*/}}
{{- define "readyroom.secretRef" -}}
{{- if .existingSecret -}}
name: {{ .existingSecret }}
key: {{ .existingSecretKey }}
{{- else if .value -}}
name: {{ include "readyroom.fullname" .root }}
key: {{ .key }}
{{- end -}}
{{- end -}}

{{/* An env entry from a secret, or nothing. Call as readyroom.secretRef plus "env" <NAME>. */}}
{{- define "readyroom.secretEnv" -}}
{{- $ref := include "readyroom.secretRef" . -}}
{{- if $ref -}}
- name: {{ .env }}
  valueFrom:
    secretKeyRef:
{{ $ref | indent 6 }}
{{- end -}}
{{- end -}}

{{/* The database password reference. The embedded database falls back to a generated password. */}}
{{- define "readyroom.dbPasswordRef" -}}
{{- $v := .Values.database -}}
{{- if or $v.existingSecret $v.password -}}
{{- include "readyroom.secretRef" (dict "root" . "value" $v.password "existingSecret" $v.existingSecret "existingSecretKey" $v.existingSecretKey "key" "db-password") -}}
{{- else if $v.embedded.enabled -}}
name: {{ include "readyroom.fullname" . }}
key: db-password
{{- else -}}
{{- fail "database.password or database.existingSecret is required: the password ReadyRoom signs in to PostgreSQL with" -}}
{{- end -}}
{{- end -}}

{{/* The public URL: publicUrl, else the first ingress or route host. */}}
{{- define "readyroom.publicUrl" -}}
{{- if .Values.publicUrl -}}
{{- .Values.publicUrl -}}
{{- else if and .Values.ingress.enabled .Values.ingress.hosts -}}
{{- $host := (first .Values.ingress.hosts).host -}}
{{- printf "%s://%s" (ternary "https" "http" (or (gt (len .Values.ingress.tls) 0) .Values.tls.enabled)) $host -}}
{{- else if and .Values.route.enabled .Values.route.host -}}
{{- printf "https://%s" .Values.route.host -}}
{{- end -}}
{{- end -}}

{{- define "readyroom.licenseSecretName" -}}
{{- if .Values.license.existingSecret -}}
{{- .Values.license.existingSecret -}}
{{- else if .Values.license.file -}}
{{- printf "%s-license" (include "readyroom.fullname" .) -}}
{{- end -}}
{{- end -}}

{{- define "readyroom.licenseClaimName" -}}
{{- default (printf "%s-license" (include "readyroom.fullname" .)) .Values.persistence.license.existingClaim -}}
{{- end -}}

{{/*
"true" when an ingress or route terminates TLS in front of ReadyRoom (edge or re-encrypt): ReadyRoom then reads
X-Forwarded-Proto (SERVER_FORWARD_HEADERS_STRATEGY=framework) and marks the session cookie Secure itself
(ENS_COOKIE_SECURE), since the connection it sees may be plain HTTP. Not for ssl-passthrough.
*/}}
{{- define "readyroom.edgeTls" -}}
{{- $ing := .Values.ingress -}}
{{- $passthrough := eq (toString (index ($ing.annotations | default dict) "nginx.ingress.kubernetes.io/ssl-passthrough")) "true" -}}
{{- if or (and $ing.enabled (gt (len $ing.tls) 0) (not $passthrough)) (and .Values.route.enabled (has .Values.route.tls.termination (list "edge" "reencrypt"))) -}}
true
{{- end -}}
{{- end -}}

{{- define "readyroom.scheme" -}}
{{- ternary "HTTPS" "HTTP" .Values.tls.enabled -}}
{{- end -}}

{{- define "readyroom.portName" -}}
{{- ternary "https" "http" .Values.tls.enabled -}}
{{- end -}}

{{/* Where the probes go: the metrics port (plain HTTP) when metrics.enabled, else ReadyRoom's own port. */}}
{{- define "readyroom.probePort" -}}
{{- if .Values.metrics.enabled -}}metrics{{- else -}}{{ include "readyroom.portName" . }}{{- end -}}
{{- end -}}

{{- define "readyroom.probeScheme" -}}
{{- if .Values.metrics.enabled -}}HTTP{{- else -}}{{ include "readyroom.scheme" . }}{{- end -}}
{{- end -}}

{{/*
The Deployment strategy: strategy when set; otherwise RollingUpdate, except Recreate while the license is on a
ReadWriteOnce volume, which a new pod on another node could not attach while the old one still holds it.
*/}}
{{- define "readyroom.strategy" -}}
{{- $v := .Values -}}
{{- if $v.strategy -}}
{{- toYaml $v.strategy -}}
{{- else if and $v.persistence.license.enabled (not (has "ReadWriteMany" $v.persistence.license.accessModes)) -}}
type: Recreate
{{- else -}}
type: RollingUpdate
rollingUpdate:
  maxSurge: 1
  maxUnavailable: 0
{{- end -}}
{{- end -}}
