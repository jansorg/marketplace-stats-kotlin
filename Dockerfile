FROM eclipse-temurin:22-jre
LABEL maintainer="Joachim Ansorg <mail@ja-dev.eu>"
LABEL org.opencontainers.image.source="https://github.com/jansorg/marketplace-stats-kotlin"

RUN sed -i '/en_/s/^# //g' /etc/locale.gen
RUN sed -i '/de_/s/^# //g' /etc/locale.gen
RUN locale-gen

RUN mkdir -p /opt/app
COPY ./build/libs/marketplace-stats-all.jar /opt/app/marketplace-stats-all.jar

EXPOSE 8080
CMD ["java", "-server", "-Xmx1024m", "-jar", "/opt/app/marketplace-stats-all.jar"]