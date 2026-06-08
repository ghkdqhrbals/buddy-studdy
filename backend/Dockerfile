FROM gradle:8.14.2-jdk24-alpine AS build

WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts ./
COPY libs ./libs
COPY src ./src
RUN gradle --no-daemon clean bootJar

FROM eclipse-temurin:24-jre-alpine

ENV APP_PORT=8080
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
COPY --from=build /workspace/build/libs/*.jar /app/buddystuddy-backend.jar
RUN chown -R app:app /app

USER app
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/health >/dev/null || exit 1

CMD ["java", "-jar", "/app/buddystuddy-backend.jar"]
