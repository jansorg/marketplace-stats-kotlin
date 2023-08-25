FROM eclipse-temurin:17-alpine
LABEL maintainer="Joachim Ansorg <mail@ja-dev.eu>"
LABEL org.opencontainers.image.source="https://github.com/jansorg/marketplace-stats-kotlin"

RUN mkdir -p /opt/app
COPY ./build/libs/marketplace-stats-all.jar /opt/app/marketplace-stats-all.jar
EXPOSE 8080
CMD ["java", "-server", "-Xmx1024m", "-jar", "/opt/app/marketplace-stats-all.jar"]