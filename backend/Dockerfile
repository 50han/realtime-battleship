# ---- build stage -------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first, so a source-only change does not re-download the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- runtime stage -----------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Run unprivileged.
RUN useradd --system --create-home --shell /usr/sbin/nologin battleship
COPY --from=build /build/target/battleship-server.jar ./battleship-server.jar
USER battleship

EXPOSE 8080

# Container memory limits, not the host's, decide the heap size.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

# The base image has no curl or wget, so the check speaks HTTP over bash's
# /dev/tcp. It must be bash, not sh — /bin/sh here is dash, which has no
# /dev/tcp support.
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
  CMD ["/bin/bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /api/health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && grep -q '200 OK' <&3"]

ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar /app/battleship-server.jar"]
