# Design section 17 -- Docker packaging for identity-data-deletion-service.
#
# Build from the repository root so the build context is this module:
#     docker build -t identity-data-deletion-service:1.0.0 deletion-service
#
# The image ships application.yml defaults that point at 127.0.0.1, which is the
# container itself, so every datasource and the MinIO endpoint MUST be overridden
# at run time. Spring maps APP_DATASOURCES_<KEY>_URL onto app.datasources.<key>.url.
#
#     docker run --rm -p 8096:8096 \
#       -e APP_DATASOURCES_IDREPO_URL=jdbc:postgresql://host.docker.internal:5448/mosip_idrepo \
#       -e APP_DATASOURCES_IDMAP_URL=jdbc:postgresql://host.docker.internal:5442/mosip_idmap \
#       -e APP_DATASOURCES_REGPRC_URL=jdbc:postgresql://host.docker.internal:5443/mosip_regprc \
#       -e APP_DATASOURCES_RESIDENT_URL=jdbc:postgresql://host.docker.internal:5444/mosip_resident \
#       -e APP_DATASOURCES_CREDENTIAL_URL=jdbc:postgresql://host.docker.internal:5445/mosip_credential \
#       -e APP_DATASOURCES_IDA_URL=jdbc:postgresql://host.docker.internal:5446/mosip_ida \
#       -e APP_DATASOURCES_AUDIT_URL=jdbc:postgresql://host.docker.internal:5447/mosip_deletion_audit \
#       -e APP_DATASOURCES_MOCKIDENTITY_URL=jdbc:postgresql://host.docker.internal:5455/mosip_mockidentitysystem \
#       -e APP_DATASOURCES_ESIGNET_URL=jdbc:postgresql://host.docker.internal:5455/mosip_esignet \
#       -e APP_MINIO_ENDPOINT=http://host.docker.internal:9000 \
#       identity-data-deletion-service:1.0.0
#
# On Linux hosts add --add-host=host.docker.internal:host-gateway, or attach the
# container to the same compose network and use the service names instead.

# ---------------------------------------------------------------- build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies first so that editing sources does not invalidate the
# dependency layer on every rebuild.
COPY pom.xml .
RUN mvn -q -B -e dependency:go-offline

COPY src ./src
RUN mvn -q -B -DskipTests package

# -------------------------------------------------------------- runtime stage
FROM eclipse-temurin:21-jre-jammy

LABEL org.opencontainers.image.title="identity-data-deletion-service"
LABEL org.opencontainers.image.description="MOSIP Collab self-service UIN and personal data deletion"
LABEL org.opencontainers.image.version="1.0.0"

# The PostgreSQL containers reject the legacy Asia/Calcutta zone alias on connect,
# so the JVM is pinned to UTC exactly as run.ps1 and start-all.ps1 do.
ENV TZ=UTC
ENV JAVA_OPTS="-Duser.timezone=UTC"

# Run unprivileged: this service only needs outbound database and S3 connections.
RUN groupadd --system deletion && useradd --system --gid deletion --create-home deletion
WORKDIR /app
COPY --from=build /build/target/identity-data-deletion-service-1.0.0.jar app.jar
RUN chown -R deletion:deletion /app
USER deletion

EXPOSE 8096

# A 401 from the secured endpoint proves the app is up and the JWT gate is live,
# which is the cheapest liveness signal this service exposes.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
  CMD sh -c 'code=$(wget -q -O /dev/null -S --method=POST http://127.0.0.1:8096/api/deletion/execute 2>&1 \
      | awk "/HTTP\\//{print \$2}" | tail -1); [ "$code" = "401" ]'

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
