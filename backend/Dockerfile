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
RUN ./gradlew bootJar --no-daemon

# Runtime stage
FROM openjdk:17-jre-slim

WORKDIR /app

# Install necessary tools
RUN apt-get update && apt-get install -y \
    curl \
    wget \
    netcat \
    && rm -rf /var/lib/apt/lists/*

# Copy jar from builder
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