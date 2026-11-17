# syntax=docker/dockerfile:1

# postgis/postgis publishes amd64-only images. Build the same PGDG packages on the
# official multi-architecture PostgreSQL base so OCI Ampere A1 runs PostGIS natively.
FROM postgres:16.16-bookworm@sha256:a49f7438ba03589f9b1ca062f2da82e899b48ea1dc50d2449fa09a70be83ebca

LABEL org.opencontainers.image.source="https://github.com/ghdtjdwn/geuneul" \
      org.opencontainers.image.description="PostgreSQL 16 with PostGIS for Geuneul on OCI ARM64"

ARG POSTGIS_VERSION=3.6.4+dfsg-2.pgdg12+1

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
      ca-certificates \
      "postgresql-${PG_MAJOR}-postgis-3=${POSTGIS_VERSION}" \
      "postgresql-${PG_MAJOR}-postgis-3-scripts=${POSTGIS_VERSION}"; \
    rm -rf /var/lib/apt/lists/*

COPY --chmod=0755 init-app-database.sh /docker-entrypoint-initdb.d/10-geuneul-app-role.sh

