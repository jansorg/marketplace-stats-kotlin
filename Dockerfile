FROM eclipse-temurin:25-jre
LABEL maintainer="Joachim Ansorg <mail@ja-dev.eu>"
LABEL org.opencontainers.image.source="https://github.com/jansorg/marketplace-stats-kotlin"

RUN sed -i '/en_/s/^# //g' /etc/locale.gen
RUN sed -i '/de_/s/^# //g' /etc/locale.gen
RUN locale-gen

RUN mkdir -p /opt/app
COPY ./build/libs/marketplace-stats-all.jar /opt/app/marketplace-stats-all.jar

EXPOSE 8080
CMD ["java", "-server", "-Xmx1512m", "-XX:MaxPermSize=192m", "-XX:+HeapDumpOnOutOfMemoryError", "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED", "-jar", "/opt/app/marketplace-stats-all.jar"]