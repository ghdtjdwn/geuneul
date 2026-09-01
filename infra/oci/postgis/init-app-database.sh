#!/usr/bin/env bash
set -Eeuo pipefail

: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${GEUNEUL_DB_PASSWORD:?GEUNEUL_DB_PASSWORD is required}"

# The application role owns its database and Flyway objects but is not a cluster
# superuser. PostGIS itself is installed once by the bootstrap administrator.
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 \
  --set app_password="$GEUNEUL_DB_PASSWORD" --set db_name="$POSTGRES_DB" <<'SQL'
CREATE EXTENSION IF NOT EXISTS postgis;

SELECT format('CREATE ROLE geuneul_app LOGIN PASSWORD %L', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'geuneul_app')
\gexec

SELECT format('ALTER ROLE geuneul_app PASSWORD %L', :'app_password')
\gexec

SELECT format('ALTER DATABASE %I OWNER TO geuneul_app', :'db_name')
\gexec

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO geuneul_app;
SQL

