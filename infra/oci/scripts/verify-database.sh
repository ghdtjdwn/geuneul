#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly oci_dir="$(cd -- "${script_dir}/.." && pwd)"
readonly env_file="${1:-}"
readonly source_counts="${2:-}"
readonly target_counts="${3:-}"

source "${script_dir}/runtime-lib.sh"

fail() {
  printf 'Database verification failed: %s\n' "$1" >&2
  exit 1
}

[[ -n "$env_file" && -n "$source_counts" ]] \
  || fail "usage: verify-database.sh production.env source.table-counts.tsv [target-output.tsv]"
[[ -f "$source_counts" && ! -L "$source_counts" ]] || fail "source table-count file is invalid"
"${script_dir}/validate-runtime.sh" "$env_file"
resolve_compose

output_path="${target_counts:-$(mktemp /tmp/geuneul-target-counts.XXXXXX)}"
GEUNEUL_ENV_FILE="$env_file" "${COMPOSE[@]}" \
  --env-file "$env_file" --file "${oci_dir}/compose.production.yml" \
  exec -T postgres psql --username geuneul_app --dbname geuneul \
    --no-align --tuples-only --field-separator $'\t' --set ON_ERROR_STOP=1 <<'SQL' >"$output_path"
SELECT format(
  'SELECT %L AS table_name, count(*) AS row_count FROM %I.%I;',
  table_name, table_schema, table_name
)
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY table_name
\gexec
SQL

diff -u "$source_counts" "$output_path"

checks="$(
  GEUNEUL_ENV_FILE="$env_file" "${COMPOSE[@]}" \
    --env-file "$env_file" --file "${oci_dir}/compose.production.yml" \
    exec -T postgres psql --username geuneul_app --dbname geuneul \
      --no-align --tuples-only --field-separator '|' --set ON_ERROR_STOP=1 <<'SQL'
SELECT extversion FROM pg_extension WHERE extname = 'postgis';
SELECT installed_rank || '|' || COALESCE(version, 'R') || '|' || success
FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;
SELECT count(*) FROM pg_constraint WHERE contype IN ('p', 'u', 'f', 'c');
SELECT count(*) FROM pg_indexes WHERE schemaname = 'public';
SELECT COALESCE((SELECT ST_SRID(geom)::text FROM places WHERE geom IS NOT NULL LIMIT 1), 'EMPTY');
SQL
)"
postgis_version="$(sed -n '1p' <<<"$checks")"
flyway_status="$(sed -n '2p' <<<"$checks")"
constraint_count="$(sed -n '3p' <<<"$checks")"
index_count="$(sed -n '4p' <<<"$checks")"
spatial_srid="$(sed -n '5p' <<<"$checks")"
[[ "$postgis_version" =~ ^3\. ]] || fail "PostGIS extension version is missing"
[[ "$flyway_status" == *'|true' || "$flyway_status" == *'|t' ]] \
  || fail "latest Flyway migration is not successful"
[[ "$constraint_count" =~ ^[1-9][0-9]*$ ]] || fail "no relational constraints were restored"
[[ "$index_count" =~ ^[1-9][0-9]*$ ]] || fail "no public indexes were restored"
[[ "$spatial_srid" == "4326" || "$spatial_srid" == "EMPTY" ]] \
  || fail "spatial SRID verification did not return 4326"

printf 'Database row counts, Flyway state, constraints, indexes, and PostGIS query verified.\n'
printf 'Target table counts: %s\n' "$output_path"
