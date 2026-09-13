# Use Red Hat Universal Base Image 9 (UBI9) with OpenJDK 21
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime

USER root
# krb5-workstation: Kerberos configuration (/etc/krb5.conf) and the klist/kinit tools for checking the keytab
# used by Windows sign-in (application.windows-auth). The JVM validates tickets itself, so the app needs
# nothing else.
RUN microdnf update -y \
    && microdnf install -y --nodocs krb5-workstation \
    && microdnf clean all \
    && mkdir -p /etc/in-n-out \
    && chmod 0750 /etc/in-n-out

# Set working directory inside container
WORKDIR /app

# Copy the Spring Boot jar into the container
# Assuming your jar is named app.jar
COPY build/libs/*.jar app.jar

# Expose the port the app runs on
EXPOSE 8443

# Windows sign-in, when enabled, reads mounted files (never bake these into the image):
#   /etc/krb5.conf                your realm and domain controllers
#   /etc/in-n-out/http.keytab     the key for the app's HTTP/<host> service principal

# Allow JVM options to be passed via ENV variable, default empty
ENV JAVA_OPTS=""

# Entry point using JAVA_OPTS
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
