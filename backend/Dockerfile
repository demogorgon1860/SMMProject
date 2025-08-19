# Runtime stage - using pre-built JAR
FROM eclipse-temurin:17-jre-jammy

# Install curl for health checks and dumb-init for signal handling
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl dumb-init && \
    rm -rf /var/lib/apt/lists/*

# Add non-root user
RUN addgroup --system appgroup && \
    adduser --system --no-create-home --shell /bin/false --ingroup appgroup appuser && \
    mkdir -p /app && \
    chown -R appuser:appgroup /app

WORKDIR /app

# Copy the pre-built JAR
COPY build/libs/*.jar app.jar

# Switch to non-root user
USER appuser

# Container-aware JVM settings
ENV JAVA_OPTS="-Xms512m -Xmx2g \
               -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
               -XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:InitialRAMPercentage=50.0 \
               -Djava.security.egd=file:/dev/./urandom"

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Expose port
EXPOSE 8080

# Use dumb-init to handle signals properly
ENTRYPOINT ["dumb-init", "--"]

# Run application
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]