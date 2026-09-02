#!/usr/bin/env bash
set -Eeuo pipefail

readonly script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly oci_dir="$(cd -- "${script_dir}/.." && pwd)"
readonly env_file="${1:-}"
readonly dump_path="${2:-}"
readonly checksum_path="${3:-${dump_path}.sha256}"

source "${script_dir}/runtime-lib.sh"

fail() {
  printf 'Database restore refused: %s\n' "$1" >&2
  exit 1
}

[[ "${RESTORE_CONFIRM:-}" == "RESTORE_GEUNEUL" ]] \
  || fail "set RESTORE_CONFIRM=RESTORE_GEUNEUL for the reviewed empty target database"
[[ -n "$env_file" && -n "$dump_path" ]] \
  || fail "usage: restore-database.sh production.env backup.dump [backup.dump.sha256]"
require_regular_0600_file "$env_file" "production environment"
[[ "$dump_path" == /* && -f "$dump_path" && ! -L "$dump_path" ]] \
  || fail "dump must be an absolute regular non-symlink file"
[[ "$checksum_path" == /* && -f "$checksum_path" && ! -L "$checksum_path" ]] \
  || fail "checksum must be an absolute regular non-symlink file"

"${script_dir}/validate-runtime.sh" "$env_file"
[[ "$(wc -l <"$checksum_path" | tr -d '[:space:]')" == "1" ]] \
  || fail "checksum file must contain exactly one entry"
read -r expected_digest checksum_filename < <(awk '{ print $1, $2 }' "$checksum_path")
checksum_filename="${checksum_filename#\*}"
[[ "$expected_digest" =~ ^[0-9a-f]{64}$ ]] || fail "checksum digest is invalid"
[[ "$checksum_filename" == "$(basename "$dump_path")" ]] \
  || fail "checksum entry must name the supplied dump basename"
actual_digest="$(sha256sum "$dump_path" | awk '{ print $1 }')"
[[ "$actual_digest" == "$expected_digest" ]] || fail "dump checksum does not match"
resolve_compose "$oci_dir"

if [[ -n "$(GEUNEUL_ENV_FILE="$env_file" "${COMPOSE[@]}" \
  --env-file "$env_file" --file "${oci_dir}/compose.production.yml" \
  ps --status running --quiet app)" ]]; then
  fail "application container must be stopped before restore"
fi

existing_tables="$(
  GEUNEUL_ENV_FILE="$env_file" "${COMPOSE[@]}" \
    --env-file "$env_file" --file "${oci_dir}/compose.production.yml" \
    exec -T postgres psql --username geuneul_admin --dbname geuneul \
      --no-align --tuples-only --set ON_ERROR_STOP=1 \
      --command "SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename <> 'spatial_ref_sys';"
)"
[[ "$existing_tables" == "0" ]] || fail "target database is not empty"

restore_list="/tmp/geuneul-restore.$$.list"
cleanup_restore_list() {
  GEUNEUL_ENV_FILE="$env_file" "${COMPOSE[@]}" \
    --env-file "$env_file" --file "${oci_dir}/compose.production.yml" \
    exec -T postgres rm -f -- "$restore_list" >/dev/null 2>&1 || true
}
trap cleanup_restore_list EXIT
excluded_count="$(
  GEUNEUL_ENV_FILE="$env_file" "${COMPOSE[@]}" \
    --env-file "$env_file" --file "${oci_dir}/compose.production.yml" \
    exec -T postgres pg_restore --list <"$dump_path" \
    | grep -c 'TABLE DATA public spatial_ref_sys'
)"
[[ "$excluded_count" == "1" ]] \
  || fail "dump must contain exactly one PostGIS-managed spatial_ref_sys table-data entry"

GEUNEUL_ENV_FILE="$env_file" "${COMPOSE[@]}" \
  --env-file "$env_file" --file "${oci_dir}/compose.production.yml" \
  exec -T postgres pg_restore --list <"$dump_path" \
  | awk '/TABLE DATA public spatial_ref_sys/ { print ";" $0; next } { print }' \
  | GEUNEUL_ENV_FILE="$env_file" "${COMPOSE[@]}" \
      --env-file "$env_file" --file "${oci_dir}/compose.production.yml" \
      exec -T postgres sh -c 'cat > "$1"' sh "$restore_list"

GEUNEUL_ENV_FILE="$env_file" "${COMPOSE[@]}" \
  --env-file "$env_file" --file "${oci_dir}/compose.production.yml" \
  exec -T postgres pg_restore \
    --username geuneul_admin --dbname geuneul --role geuneul_app \
    --use-list "$restore_list" --no-owner --no-acl --no-comments --no-security-labels \
    --single-transaction --exit-on-error <"$dump_path"

GEUNEUL_ENV_FILE="$env_file" "${COMPOSE[@]}" \
  --env-file "$env_file" --file "${oci_dir}/compose.production.yml" \
  exec -T postgres rm -f -- "$restore_list"
trap - EXIT

GEUNEUL_ENV_FILE="$env_file" "${COMPOSE[@]}" \
  --env-file "$env_file" --file "${oci_dir}/compose.production.yml" \
  exec -T postgres psql --username geuneul_admin --dbname geuneul \
    --set ON_ERROR_STOP=1 --command "ANALYZE;"

printf 'Database restore completed. Run verify-database.sh before starting the application.\n'
