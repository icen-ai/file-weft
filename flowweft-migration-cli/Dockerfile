# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jre-jammy@sha256:d63bd8d9b171999cbed8576f2c76e874dd4856791a358536e5c4d407e77edc13

LABEL org.opencontainers.image.title="FlowWeft migration CLI" \
      org.opencontainers.image.description="One-shot, fail-closed FlowWeft database migration runner"

WORKDIR /opt/flowweft-migration

# Build first with :flowweft-migration-cli:installDist. The image contains only the
# runtime distribution; source trees, Gradle caches and database credentials never enter it.
COPY --chown=65532:65532 flowweft-migration-cli/build/install/flowweft-migration-cli/lib/ ./lib/

USER 65532:65532
HEALTHCHECK NONE
STOPSIGNAL SIGTERM

ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-cp", "/opt/flowweft-migration/lib/*", "ai.icen.fw.migration.cli.FlowWeftMigrationCliKt"]
