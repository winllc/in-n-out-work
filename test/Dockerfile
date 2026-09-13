# Multi-stage build: compile the Spring Boot app from source, then run it.
# Used by test/docker-compose.app.yml. Build context is the repo root.

# ---- build stage -------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
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
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime

# krb5-workstation: Kerberos configuration and the klist/kinit tools for checking the keytab used by Windows
# sign-in (application.windows-auth). The JVM validates tickets itself. /etc/in-n-out is where the keytab is
# mounted; group 0 is the image's default user's group, so it can read it.
USER root
RUN microdnf install -y --nodocs krb5-workstation  && microdnf clean all  && mkdir -p /etc/in-n-out  && chgrp 0 /etc/in-n-out  && chmod 0750 /etc/in-n-out
USER 185

WORKDIR /app

COPY --from=build /workspace/app.jar app.jar

# server.port in application.yml is 8444 (HTTPS)
EXPOSE 8444

ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
