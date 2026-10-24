FROM eclipse-temurin:27-jre
LABEL maintainer="Joachim Ansorg <mail@ja-dev.eu>"
LABEL org.opencontainers.image.source="https://github.com/jansorg/marketplace-stats-kotlin"

RUN sed -i '/en_/s/^# //g' /etc/locale.gen
RUN sed -i '/de_/s/^# //g' /etc/locale.gen
RUN locale-gen

RUN mkdir -p /opt/app
COPY ./build/libs/marketplace-stats-all.jar /opt/app/marketplace-stats-all.jar

# the persistent cache speeds up restarts, mount a volume at this directory to keep it when the container is replaced.
# An empty MARKETPLACE_CACHE_DIR disables it.
ENV MARKETPLACE_CACHE_DIR=/root/.cache/marketplace-stats
RUN mkdir -p /root/.cache/marketplace-stats

EXPOSE 8080
CMD ["java", "-server", "-Xmx1512m", "-XX:+HeapDumpOnOutOfMemoryError", "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED", "-jar", "/opt/app/marketplace-stats-all.jar"]