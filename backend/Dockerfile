FROM ghcr.io/graalvm/native-image-community:24 AS build

WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY domain ./domain
COPY application ./application
COPY infra ./infra
COPY tutor ./tutor
RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon clean :tutor:nativeCompile

FROM oraclelinux:9-slim

ENV APP_PORT=8080
WORKDIR /app

RUN microdnf install -y curl shadow-utils && \
    microdnf clean all && \
    groupadd --system app && \
    useradd --system --gid app --home-dir /app --shell /sbin/nologin app
COPY --from=build /workspace/tutor/build/native/nativeCompile/buddystuddy-backend /app/buddystuddy-backend
RUN chown -R app:app /app

USER app
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/health >/dev/null || exit 1

CMD ["/app/buddystuddy-backend"]
