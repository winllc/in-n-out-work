# ReadyRoom production image, built from source.
#
#     docker build -t readyroom:latest .
#     docker run -d --name readyroom -p 8181:8181 \
#         -e DB_HOST=db.internal -e LDAP_HOST=ldap.internal ... \
#         -v readyroom-license:/var/lib/ens/readyroom \
#         readyroom:latest
#
# Without a license only sign-in and the license screen (/app/admin/license) work: an administrator
# signs in and uploads the ENS license there. See Licensing in the README. test/Dockerfile is the same
# build for the compose stacks.

# The base images, overridable for a registry mirror (--build-arg BUILD_IMAGE=mirror.example/...).
ARG BUILD_IMAGE=eclipse-temurin:21-jdk
ARG RUNTIME_IMAGE=registry.access.redhat.com/ubi9/openjdk-21-runtime

# ---- build stage -------------------------------------------------------------
FROM ${BUILD_IMAGE} AS build
WORKDIR /workspace

# The Gradle wrapper and build scripts first, so the dependency download is cached until they change.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle build.gradle gradle.properties ./
# Normalize wrapper line endings (the repo may be checked out with CRLF on Windows) and fetch dependencies.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew \
 && ./gradlew --no-daemon dependencies > /dev/null

# Then the sources, and the executable boot jar staged as app.jar (not the -plain jar).
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar \
 && cp "$(find build/libs -name '*.jar' ! -name '*-plain.jar' | head -n1)" app.jar

# ---- runtime stage -----------------------------------------------------------
FROM ${RUNTIME_IMAGE}

LABEL org.opencontainers.image.title="ReadyRoom" \
      org.opencontainers.image.description="ReadyRoom workforce check-in/out and accountability" \
      org.opencontainers.image.vendor="ENS Solutions, LLC" \
      org.opencontainers.image.url="https://ens-solutions.com/" \
      com.enssolutions.license.path="/var/lib/ens/readyroom/license.lic"

# krb5-workstation: Kerberos configuration and the klist/kinit tools for checking the keytab used by Windows
# sign-in (application.windows-auth). The JVM validates tickets itself. /etc/in-n-out is where the keytab is
# mounted; group 0 is the runtime user's group (and OpenShift's arbitrary UIDs'), so it can read it.
USER root
RUN microdnf install -y --nodocs krb5-workstation \
 && microdnf clean all \
 && mkdir -p /etc/in-n-out /app \
 && chgrp 0 /etc/in-n-out && chmod 0750 /etc/in-n-out \
 && chgrp 0 /app && chmod 0775 /app

# The ENS Solutions license and this installation's licensing state, in one persistent, WRITABLE
# directory: the license screen's upload writes license.lic here, and the installation id (which
# licenses can be bound to) and the highest revision seen of each license live beside it. Keep it on a
# volume that survives upgrades; lose it and the installation gets a new id. Never COPY a license into
# the image. Owned by the runtime user, group 0 with rwx for OpenShift's arbitrary UIDs.
ENV ENS_LICENSE_STATE_DIR=/var/lib/ens/readyroom \
    ENS_LICENSE_FILE=/var/lib/ens/readyroom/license.lic
RUN mkdir -p /var/lib/ens/readyroom \
 && chown -R 185:0 /var/lib/ens \
 && chmod -R 0770 /var/lib/ens
VOLUME /var/lib/ens

# The audit log file, when ENS_AUDIT_FILE is set (e.g. /var/log/ens/readyroom/audit.jsonl; see Audit logging
# in the README). Writable by the runtime user, group 0 with rwx for OpenShift's arbitrary UIDs. Mount the
# ens-audit volume here to keep it across upgrades.
RUN mkdir -p /var/log/ens/readyroom \
 && chown -R 185:0 /var/log/ens \
 && chmod -R 0770 /var/log/ens
VOLUME /var/log/ens

# Windows sign-in, when enabled, reads mounted files (never bake these into the image):
#   /etc/krb5.conf                your realm and domain controllers
#   /etc/in-n-out/http.keytab     the key for the app's HTTP/<host> service principal

WORKDIR /app
COPY --from=build --chown=185:0 /workspace/app.jar app.jar
USER 185

# server.port in application.yml is 8181, plain HTTP unless SSL_ENABLED=true
EXPOSE 8181

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# /actuator/** is open to unauthenticated callers. Plain HTTP by default; with SSL_ENABLED=true the same
# port speaks TLS, hence the second try.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS -o /dev/null "http://127.0.0.1:${SERVER_PORT:-8181}/actuator/health" \
     || curl -kfsS -o /dev/null "https://127.0.0.1:${SERVER_PORT:-8181}/actuator/health" \
     || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
