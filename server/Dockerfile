# The gateway of NetworkHelm in a container: the browser opens https://<host>:8443 and goes to the routers through it.
# The jar comes first, then the image, both from the root of the repository:
#   ./gradlew :server:buildFatJar
#   docker build -t networkhelm-gateway server
# The account, the certificate and what the gateway keeps for the browser live in the volume /data.
FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 networkhelm \
    && useradd --uid 10001 --gid networkhelm --home-dir /data --shell /usr/sbin/nologin networkhelm \
    && mkdir /data \
    && chown networkhelm /data
COPY build/libs/networkhelm-gateway.jar /opt/networkhelm/networkhelm-gateway.jar
COPY --chmod=755 packaging/docker-entrypoint.sh /opt/networkhelm/docker-entrypoint.sh
ENV NETWORKHELM_GATEWAY_HOME=/data
VOLUME /data
EXPOSE 8443
ENTRYPOINT ["/opt/networkhelm/docker-entrypoint.sh", "java", "-jar", "/opt/networkhelm/networkhelm-gateway.jar"]
