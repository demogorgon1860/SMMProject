# Multi-stage build: Build JAR in container
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /app

# Install required tools
RUN apk add --no-cache git dos2unix bash

# Copy gradle wrapper and build files
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle ./

# Fix Windows line endings and make executable
RUN dos2unix gradlew && chmod +x gradlew

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon || true

# Copy source code
COPY src src

# Build the JAR (skip tests and spotless for faster build)
RUN ./gradlew clean build -x test -x spotlessCheck -x spotlessJavaCheck --no-daemon

# Runtime stage
FROM eclipse-temurin:17-jdk-alpine

# Install curl for health checks
RUN apk add --no-cache curl

WORKDIR /app

# Copy JAR from builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

# Set permissions
RUN chmod +x /app/app.jar

EXPOSE 8080

# Container-aware JVM settings with Java 17 compatibility
ENV JAVA_OPTS="-Xms512m \
               -XX:+UseG1GC \
               -XX:MaxGCPauseMillis=200 \
               -XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -Djava.security.egd=file:/dev/./urandom \
               --add-opens java.base/java.nio=ALL-UNNAMED \
               --add-opens java.base/sun.nio.ch=ALL-UNNAMED"

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
