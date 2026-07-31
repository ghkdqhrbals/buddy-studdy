# syntax=docker/dockerfile:1.7

ARG BACKEND_RUNTIME=jvm

FROM scratch AS backend-source
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts /workspace/
COPY gradle /workspace/gradle
COPY domain /workspace/domain
COPY application /workspace/application
COPY infra /workspace/infra
COPY tutor /workspace/tutor

FROM ghcr.io/graalvm/native-image-community:25 AS build-native
WORKDIR /workspace
COPY --from=backend-source /workspace /workspace
RUN chmod +x ./gradlew
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon clean :tutor:nativeCompile

FROM eclipse-temurin:25-jdk-noble AS build-jvm
WORKDIR /workspace
COPY --from=backend-source /workspace /workspace
RUN chmod +x ./gradlew
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon clean :tutor:bootJar

FROM oraclelinux:9-slim AS runtime-native

ENV APP_PORT=8080
ENV FLYWAY_LOCATIONS=filesystem:/app/db/migration-mysql
ENV BUDDYSTUDY_RUNTIME=native
WORKDIR /app

RUN microdnf install -y curl-minimal shadow-utils && \
    microdnf clean all && \
    groupadd --system app && \
    useradd --system --gid app --home-dir /app --shell /sbin/nologin app
COPY --chown=app:app --from=build-native /workspace/tutor/build/native/nativeCompile/buddystudy-backend /app/buddystudy-backend
COPY --chown=app:app --from=build-native /workspace/tutor/src/main/resources/db/migration-mysql /app/db/migration-mysql

USER app
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=3 \
    CMD ["curl", "--fail", "--silent", "--show-error", "--max-time", "5", "http://127.0.0.1:8080/api/v1/health/dependencies"]

CMD ["/app/buddystudy-backend"]

FROM eclipse-temurin:25-jre-noble AS runtime-jvm

ENV APP_PORT=8080
ENV FLYWAY_LOCATIONS=filesystem:/app/db/migration-mysql
ENV BUDDYSTUDY_RUNTIME=jvm
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError"
WORKDIR /app

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --system app && \
    useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app
COPY --chown=app:app --from=build-jvm /workspace/tutor/build/libs/buddystudy-backend.jar /app/buddystudy-backend.jar
COPY --chown=app:app --from=build-jvm /workspace/tutor/src/main/resources/db/migration-mysql /app/db/migration-mysql

USER app
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=3 \
    CMD ["curl", "--fail", "--silent", "--show-error", "--max-time", "5", "http://127.0.0.1:8080/api/v1/health/dependencies"]

CMD ["java", "-jar", "/app/buddystudy-backend.jar"]

FROM runtime-${BACKEND_RUNTIME} AS final

ARG BACKEND_RUNTIME
LABEL io.buddystudy.backend.runtime="${BACKEND_RUNTIME}"
