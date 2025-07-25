# Multi-stage build for SMM Panel application
FROM openjdk:17-jdk-slim as build

# Install Maven and security updates
RUN apt-get update && \
    apt-get install -y --no-install-recommends maven && \
    apt-get upgrade -y && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

# Create non-root build user
RUN groupadd -r builduser && useradd -r -g builduser builduser

# Set working directory
WORKDIR /app

# Copy Maven files for dependency caching
COPY --chown=builduser:builduser backend/build.gradle backend/gradle.properties ./
COPY --chown=builduser:builduser backend/gradle ./gradle/
COPY --chown=builduser:builduser backend/gradlew ./

# Make gradlew executable
RUN chmod +x ./gradlew

# Download dependencies (this layer will be cached)
USER builduser
RUN ./gradlew dependencies --no-daemon

# Copy source code
COPY --chown=builduser:builduser backend/src ./src/

# Build application
RUN ./gradlew clean bootJar --no-daemon -x test

# Production stage
FROM openjdk:17-jre-slim

# Install security updates and curl for health checks
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl dumb-init && \
    apt-get upgrade -y && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

# Create application user with specific UID/GID for security
RUN groupadd -r -g 1001 smmapp && useradd -r -u 1001 -g smmapp smmapp

# Set working directory
WORKDIR /app

# Copy built JAR from build stage
COPY --from=build --chown=smmapp:smmapp /app/build/libs/*.jar app.jar

# Create logs directory and set permissions
RUN mkdir -p /app/logs && \
    mkdir -p /app/tmp && \
    chown -R smmapp:smmapp /app && \
    chmod 755 /app && \
    chmod 755 /app/logs && \
    chmod 755 /app/tmp

# Switch to application user
USER smmapp

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# JVM options for containerized environment with security enhancements
ENV JAVA_OPTS="-Xms512m -Xmx1024m \
               -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
               -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 \
               -Djava.security.egd=file:/dev/./urandom \
               -Djava.awt.headless=true \
               -XX:+UnlockExperimentalVMOptions \
               -XX:+UseCGroupMemoryLimitForHeap \
               -Djava.security.policy=all.policy \
               -Djava.net.preferIPv4Stack=true \
               -Dserver.tomcat.basedir=/app/tmp \
               -Dspring.profiles.active=docker"

# Use dumb-init to handle signals properly
ENTRYPOINT ["dumb-init", "--"]

# Run application with proper signal handling
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
