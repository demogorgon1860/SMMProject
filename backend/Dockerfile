FROM openjdk:17-jdk-slim AS builder

WORKDIR /app

# Copy gradle files
COPY build.gradle settings.gradle gradlew ./
COPY gradle ./gradle

# Download dependencies
RUN ./gradlew dependencies --no-daemon

# Copy source code
COPY src ./src

# Build application
RUN gradle build --no-daemon -x test

# Runtime stage
FROM eclipse-temurin:17-jre-jammy

# Add non-root user
RUN addgroup --system appgroup && \
    adduser --system --no-create-home --shell /bin/false --group appgroup appuser && \
    mkdir -p /app && \
    chown -R appuser:appgroup /app

WORKDIR /app

# Copy the built application from build stage
COPY --from=build /workspace/app/build/libs/*.jar app.jar
COPY --chown=appuser:appgroup src/main/resources/application.properties ./config/
COPY --from=builder /app/build/libs/*.jar app.jar

# Create non-root user
RUN useradd -m -s /bin/bash appuser && chown -R appuser:appuser /app
USER appuser

# JVM memory settings
ENV JAVA_OPTS="-Xms512m -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Expose port
EXPOSE 8080

# Run application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar app.jar"]