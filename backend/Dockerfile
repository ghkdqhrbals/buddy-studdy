FROM ghcr.io/graalvm/native-image-community:25 AS build

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

RUN microdnf install -y shadow-utils && \
    microdnf clean all && \
    groupadd --system app && \
    useradd --system --gid app --home-dir /app --shell /sbin/nologin app
COPY --from=build /workspace/tutor/build/native/nativeCompile/buddystudy-backend /app/buddystudy-backend
COPY --from=build /workspace/tutor/src/main/resources/db/migration /app/db/migration
RUN chown -R app:app /app

USER app
EXPOSE 8080

CMD ["/app/buddystudy-backend"]
