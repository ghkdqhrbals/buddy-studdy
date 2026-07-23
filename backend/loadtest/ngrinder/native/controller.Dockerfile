FROM eclipse-temurin:11-jre-jammy

LABEL org.opencontainers.image.source="https://github.com/naver/ngrinder"
LABEL org.opencontainers.image.version="3.5.9-p1"

ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"
ENV NGRINDER_HOME="/opt/ngrinder-controller"

COPY controller.war /opt/ngrinder-controller-3.5.9-p1.war

EXPOSE 80 16001 12000-12009
VOLUME ["/opt/ngrinder-controller"]

CMD ["java", "-jar", "-Djava.io.tmpdir=/opt/ngrinder-controller/lib", "/opt/ngrinder-controller-3.5.9-p1.war", "--port", "80"]
