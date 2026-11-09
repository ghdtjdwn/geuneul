# syntax=docker/dockerfile:1

# postgis/postgis publishes amd64-only images. Build the same PGDG packages on the
# official multi-architecture PostgreSQL base so OCI Ampere A1 runs PostGIS natively.
FROM postgres:18.6-bookworm@sha256:f1ea2a1a4fb7b68d4836be1fdcb8a6466a89b072158bf9d97663600538c294c5

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

