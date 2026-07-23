FROM eclipse-temurin:11-jre-jammy

LABEL org.opencontainers.image.source="https://github.com/naver/ngrinder"
LABEL org.opencontainers.image.version="3.5.9-p1"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates tar \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"
ENV NGRINDER_AGENT_BASE="/opt/ngrinder-agent"
ENV NGRINDER_AGENT_HOME="/opt/ngrinder-agent/.ngrinder-agent"

COPY run-agent.sh /usr/local/bin/run-agent

VOLUME ["/opt/ngrinder-agent"]
ENTRYPOINT ["/usr/local/bin/run-agent"]
