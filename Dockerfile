# Multi-stage build for SMM Panel application
FROM eclipse-temurin:17-jdk-jammy AS builder

# Install security updates
RUN apt-get update && \
    apt-get upgrade -y && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

# Create non-root build user
RUN groupadd -r builduser && \
    useradd -r -g builduser -m builduser

# Set working directory
WORKDIR /app

# Copy Gradle wrapper and config files for dependency caching
COPY --chown=builduser:builduser backend/build.gradle backend/settings.gradle ./
COPY --chown=builduser:builduser backend/gradle ./gradle/
COPY --chown=builduser:builduser backend/gradlew ./
# Copy gradle.properties if it exists
COPY --chown=builduser:builduser backend/gradle.propertie[s] ./

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
FROM eclipse-temurin:17-jre-jammy

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
COPY --from=builder --chown=smmapp:smmapp /app/build/libs/*.jar app.jar

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
               -XX:InitialRAMPercentage=50.0 \
               -Djava.security.egd=file:/dev/./urandom \
               -Djava.awt.headless=true \
               -XX:+ExitOnOutOfMemoryError \
               -Djava.net.preferIPv4Stack=true \
               -Dserver.tomcat.basedir=/app/tmp \
               -Dspring.profiles.active=docker"

# Use dumb-init to handle signals properly
ENTRYPOINT ["dumb-init", "--"]

# Run application with proper signal handling
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
