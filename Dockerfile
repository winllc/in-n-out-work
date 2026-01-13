# Use Red Hat Universal Base Image 9 (UBI9) with OpenJDK 17
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime

USER root
RUN microdnf update -y \
    && microdnf clean all

# Set working directory inside container
WORKDIR /app

# Copy the Spring Boot jar into the container
# Assuming your jar is named app.jar
COPY build/libs/*.jar app.jar

# Expose the port the app runs on
EXPOSE 8443

# Allow JVM options to be passed via ENV variable, default empty
ENV JAVA_OPTS=""

# Entry point using JAVA_OPTS
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]