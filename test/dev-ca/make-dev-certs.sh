#!/usr/bin/env bash
# Creates a certificate authority for this machine only, and from it a server keystore and client
# certificates for the seeded users, so a local ReadyRoom can run with TLS and certificate sign-in.
#
#   test/dev-ca/make-dev-certs.sh [user ...]      (default users: user1 user3)
#
# Everything is written to test/dev-ca/out/, which git ignores. Nothing here is ever committed: a CA
# whose keys are in a repository can issue a certificate for anybody, which is why the old shared
# "WINLLC DEV CA" was retired. Run it again to add users; the CA is kept unless out/ is deleted.
#
#   out/ca.crt, out/ca.key       the CA (trust ca.crt only on this machine)
#   out/localhost.p12            server keystore: localhost and 127.0.0.1 (DEV_SERVER_SANS to change)
#   out/truststore.p12           the CA, for server.ssl.trust-store
#   out/<user>.p12               a client certificate whose subject is the user's LDAP DN
#
# All keystores use the password in DEV_CERT_PASSWORD (default: changeit). Needs openssl and keytool.
set -euo pipefail

cd "$(dirname "$0")"
out=out
pass=${DEV_CERT_PASSWORD:-changeit}
sans=${DEV_SERVER_SANS:-DNS:localhost,IP:127.0.0.1}
# The seeded users' entries are cn=<user>,ou=Users,dc=winllc,dc=com; by default a client certificate's
# subject must equal the LDAP DN, so the subject is built the same way (most significant first).
subject_base=${DEV_USER_SUBJECT_BASE:-/DC=com/DC=winllc/OU=Users}
[ $# -gt 0 ] && users=("$@") || users=(user1 user3)

command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }
command -v keytool >/dev/null || { echo "keytool (from a JDK) is required" >&2; exit 1; }

mkdir -p "$out"
chmod 700 "$out"
umask 077

if [ ! -f "$out/ca.key" ]; then
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes \
        -keyout "$out/ca.key" -out "$out/ca.crt" -days 825 \
        -subj "/O=ReadyRoom Development/CN=ReadyRoom Dev CA $(hostname -s 2>/dev/null || echo local) $(date +%Y%m%d)" \
        -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
        -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null
    echo "Created a new development CA: $out/ca.crt"
fi

# issue NAME SUBJECT EXTENSIONS: a key and a certificate signed by the CA, packed into NAME.p12
issue() {
    local name=$1 subject=$2 extensions=$3
    openssl req -new -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes \
        -keyout "$out/$name.key" -out "$out/$name.csr" -subj "$subject" 2>/dev/null
    openssl x509 -req -in "$out/$name.csr" -CA "$out/ca.crt" -CAkey "$out/ca.key" -CAcreateserial \
        -days 397 -out "$out/$name.crt" -extfile <(printf '%s\n' "$extensions") 2>/dev/null
    openssl pkcs12 -export -name "$name" -inkey "$out/$name.key" -in "$out/$name.crt" \
        -certfile "$out/ca.crt" -passout "pass:$pass" -out "$out/$name.p12"
    rm -f "$out/$name.csr"
    echo "  $out/$name.p12  ($(openssl x509 -in "$out/$name.crt" -noout -subject -nameopt RFC2253 | sed 's/^subject=//'))"
}

echo "Issuing:"
issue localhost "/CN=localhost" "basicConstraints=CA:FALSE
keyUsage=critical,digitalSignature
extendedKeyUsage=serverAuth
subjectAltName=$sans"
for user in "${users[@]}"; do
    issue "$user" "$subject_base/CN=$user" "basicConstraints=CA:FALSE
keyUsage=critical,digitalSignature
extendedKeyUsage=clientAuth"
done

rm -f "$out/truststore.p12"
keytool -importcert -noprompt -alias readyroom-dev-ca -file "$out/ca.crt" \
    -keystore "$out/truststore.p12" -storetype PKCS12 -storepass "$pass" >/dev/null
echo "  $out/truststore.p12  (the CA)"
