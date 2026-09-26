# syntax=docker/dockerfile:1
# Builds the zeta CLI image; the default command is the permanently running `zeta probe`, which pushes
# metrics and traces via OTLP and serves /healthz and /readyz on port 8080.
# State (the profile database) lives under /data via XDG_CONFIG_HOME — mount a volume there.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY buildSrc ./buildSrc
COPY connector ./connector
COPY cli ./cli
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain :cli:installDist -x test

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home-dir /data --create-home --shell /usr/sbin/nologin zeta
COPY --from=build /src/cli/build/install/zeta /opt/zeta
ENV PATH="/opt/zeta/bin:${PATH}" \
    XDG_CONFIG_HOME=/data \
    XDG_CACHE_HOME=/data/cache \
    JAVA_OPTS="-XX:MaxRAMPercentage=60"
USER zeta
WORKDIR /data
EXPOSE 8080
# /healthz is the prober's own liveness: its probe loops wake up on time and no probe hangs past its
# timeout. The image has no curl, so bash's /dev/tcp speaks the request; it costs no JVM start. Follows
# ZETA_PROBE_HEALTH_PORT (0 disables the endpoints and therefore fails this check).
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${ZETA_PROBE_HEALTH_PORT:-8080} && printf 'GET /healthz HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && head -n1 <&3 | grep -q ' 200 '"]
ENTRYPOINT ["zeta"]
CMD ["probe", "-v"]
