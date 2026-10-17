# Multi-stage build: compile the Spring Boot app from source, then run it.
# Used by docker-compose.yml and docker-compose.demo.yml. Build context is the repo root.

# ---- build stage -------------------------------------------------------------
FROM eclipse-temurin:24-jdk@sha256:7493205ffe6caa8074fa8a06a276bb1c5ac41d3dd0fd43a0db66d7f776e80b3e AS build
WORKDIR /workspace

# Copy the Gradle wrapper + build scripts first for better layer caching.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle build.gradle ./

# Then the sources.
COPY src ./src

# Normalize wrapper line endings (repo may be checked out with CRLF on Windows),
# build the executable boot jar, and stage it as app.jar.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew \
 && ./gradlew --no-daemon clean bootJar \
 && cp "$(find build/libs -name '*.jar' ! -name '*-plain.jar' | head -n1)" app.jar

# ---- runtime stage -----------------------------------------------------------
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:latest@sha256:856ee6542b8fdf09a065fd5439309fc3f3c821fb8efac48aa6191c93635dd907

# krb5-workstation: Kerberos configuration and the klist/kinit tools for checking the keytab used by Windows
# sign-in (application.windows-auth). The JVM validates tickets itself. /etc/in-n-out is where the keytab is
# mounted; group 0 is the image's default user's group, so it can read it.
USER root
RUN microdnf install -y --nodocs krb5-workstation  && microdnf clean all  && mkdir -p /etc/in-n-out  && chgrp 0 /etc/in-n-out  && chmod 0750 /etc/in-n-out

# The ENS license and licensing state, as in the production Dockerfile: one writable directory, kept on
# the compose files' ens-license volume.
ENV ENS_LICENSE_STATE_DIR=/var/lib/ens/readyroom \
    ENS_LICENSE_FILE=/var/lib/ens/readyroom/license.lic
RUN mkdir -p /var/lib/ens/readyroom && chown -R 185:0 /var/lib/ens && chmod -R 0770 /var/lib/ens
VOLUME /var/lib/ens
# The audit log file, when ENS_AUDIT_FILE is set; as in the production Dockerfile.
RUN mkdir -p /var/log/ens/readyroom && chown -R 185:0 /var/log/ens && chmod -R 0770 /var/log/ens
VOLUME /var/log/ens
USER 185

WORKDIR /app

COPY --from=build /workspace/app.jar app.jar

# server.port in application.yml is 8181, plain HTTP unless SSL_ENABLED=true
EXPOSE 8181

ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
