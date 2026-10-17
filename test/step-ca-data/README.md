# WINLLC DEV CA - development only, never trust it in production

This folder is the state of the local `step-ca` certificate authority (`test/docker-compose.yml`),
named "WINLLC DEV CA". Its root and intermediate private keys (`secrets/`) and their password are
committed to this repository, so they are **public**: anyone can issue a certificate from this CA
with any subject, including a ReadyRoom administrator's DN.

- Never add `certs/root_ca.crt`, `certs/intermediate_ca.crt` or `certs/truststore.p12` to the
  truststore of a ReadyRoom that holds real data, or to any Windows or browser trust store outside a
  developer's machine.
- Never use the keystores here (`certs/*.p12`, password `capassword`) as a real server certificate.

ReadyRoom does not trust this CA by default. Nothing in the jar or image contains a keystore or
truststore any more, and with `SSL_ENABLED=true` the application refuses to start until a key store,
and (with client certificates on) a trust store, are configured explicitly.

To run a local instance with TLS and client certificates from this CA, from the repository root:

```bash
SSL_ENABLED=true \
SERVER_SSL_KEYSTORE=file:test/step-ca-data/certs/localhost.p12 SERVER_SSL_KEYSTOREPASSWORD=capassword \
SERVER_SSL_TRUSTSTORE=file:test/step-ca-data/certs/truststore.p12 SERVER_SSL_TRUSTSTOREPASSWORD=changeit \
./gradlew bootRun --args='--spring.profiles.active=local'
```

`truststore.p12` (password `changeit`) holds this CA's root; `user1.p12` and `user3.p12` (password
`capassword`) are client certificates for the seeded users.
