# Simple Dockerfile that uses pre-built JAR
FROM eclipse-temurin:17-jdk-alpine

# Install curl for health checks
RUN apk add --no-cache curl

WORKDIR /app

# Copy JAR directly (assuming it's already built)
COPY build/libs/backend-1.0.0.jar app.jar

# Set permissions
RUN chmod +x /app/app.jar

EXPOSE 8080

# Container-aware JVM settings with Java 17 compatibility
# FIX: Removed -Xmx2g to rely on MaxRAMPercentage for dynamic heap sizing
# With 2GB ECS container: 75% = 1536MB heap, leaving 512MB for metaspace/threads/native
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