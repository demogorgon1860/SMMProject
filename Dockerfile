# Multi-stage build for SMM Panel application
FROM openjdk:17-jdk-slim as build

# Install Maven
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy Maven files for dependency caching
COPY pom.xml .
COPY backend/pom.xml backend/

# Download dependencies (this layer will be cached)
RUN mvn dependency:go-offline -B

# Copy source code
COPY backend/src backend/src

# Build application
RUN mvn clean package -DskipTests -B

# Production stage
FROM openjdk:17-jdk-slim

# Install curl for health checks
RUN apt-get update && \
    apt-get install -y curl && \
    rm -rf /var/lib/apt/lists/*

# Create application user
RUN groupadd -r smmapp && useradd -r -g smmapp smmapp

# Set working directory
WORKDIR /app

# Copy built JAR from build stage
COPY --from=build /app/backend/target/smm-panel-*.jar app.jar

# Create logs directory
RUN mkdir -p /app/logs && chown -R smmapp:smmapp /app

# Switch to application user
USER smmapp

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# JVM options for containerized environment
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
               -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 \
               -Djava.security.egd=file:/dev/./urandom"

# Run application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
