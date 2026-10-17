# Development certificates: made on your machine, never committed

`make-dev-certs.sh` creates a certificate authority for your machine only. From that CA it creates a
server keystore and client certificates for the seeded users, so you can run ReadyRoom locally with
TLS and certificate sign-in:

```bash
test/dev-ca/make-dev-certs.sh            # user1 and user3; add names to issue more
SSL_ENABLED=true \
SERVER_SSL_KEYSTORE=file:test/dev-ca/out/localhost.p12 SERVER_SSL_KEYSTOREPASSWORD=changeit \
SERVER_SSL_TRUSTSTORE=file:test/dev-ca/out/truststore.p12 SERVER_SSL_TRUSTSTOREPASSWORD=changeit \
./gradlew bootRun --args='--spring.profiles.active=local'
```

- **Browser sign-in:** import `test/dev-ca/out/user1.p12` into your browser (password `changeit`, or
  `DEV_CERT_PASSWORD` if you set it), and trust `out/ca.crt` on this machine only.
- **Matching:** each client certificate's subject is the user's LDAP DN, for example
  `CN=user3,OU=Users,DC=winllc,DC=com`, which is what ReadyRoom matches by default.
- **What git ignores:** everything in `test/dev-ca/out/` is ignored by git. Delete the folder to start
  over with a new CA.

## The retired "WINLLC DEV CA"

This repository used to contain a shared development CA, "WINLLC DEV CA", under `test/step-ca-data/`.
That included its root and intermediate **private keys**, their password, and keystores it had issued.
The repository is public, so those keys were public: anyone could issue a certificate from that CA, for
any subject.

They have been removed from the repository's entire history. **Treat that CA as compromised:**
- Remove "WINLLC DEV CA Root CA" and "WINLLC DEV CA Intermediate CA" from every trust store that has
  them: browsers, Windows certificate stores, Java truststores, Kubernetes Secrets.
- Delete any local copy of the old `test/step-ca-data/` folder.
- Never trust a certificate it issued.

ReadyRoom itself never trusts it: the jar and image contain no keystore or truststore. With
`SSL_ENABLED=true`, the application won't start until you configure a key store explicitly, plus a
trust store when client certificates are on.
