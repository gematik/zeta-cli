# syntax=docker/dockerfile:1
# Builds the zeta CLI image; the default command is the permanently running `zeta probe`.
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
EXPOSE 9464
ENTRYPOINT ["zeta"]
CMD ["probe", "-v"]
