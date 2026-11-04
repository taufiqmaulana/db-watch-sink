# DataSync server image (design 14). Build the app first, then the image:
#   ./mvnw install -DskipTests
#   docker build -t datasync .
# Runs as a non-root user; the state store (SQLite) lives in the /data volume.
FROM eclipse-temurin:21.0.8_9-jre-noble

RUN useradd --system --uid 10001 --home-dir /data datasync \
    && mkdir /data && chown datasync /data
WORKDIR /app
COPY --chown=datasync datasync-server/target/quarkus-app/ /app/

USER datasync
ENV DATASYNC_DATA_DIR=/data \
    JAVA_OPTS_APPEND="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS_APPEND -jar /app/quarkus-run.jar"]
