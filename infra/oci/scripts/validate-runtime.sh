#!/usr/bin/env bash
set -Eeuo pipefail

readonly script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly oci_dir="$(cd -- "${script_dir}/.." && pwd)"
readonly env_file="${1:-}"

source "${script_dir}/runtime-lib.sh"

fail() {
  printf 'OCI runtime validation failed: %s\n' "$1" >&2
  exit 1
}

[[ -n "$env_file" ]] || fail "usage: validate-runtime.sh /absolute/path/production.env"
[[ "$env_file" == /* ]] || fail "environment file path must be absolute"
[[ -f "$env_file" && ! -L "$env_file" ]] || fail "environment file must be a regular non-symlink file"
[[ "$(portable_file_mode "$env_file")" == "600" ]] \
  || fail "environment file mode must be 0600"
[[ -z "$(LC_ALL=C tr -d '\11\12\15\40-\176' <"$env_file")" ]] \
  || fail "environment file contains unsupported control bytes"

required=(
  COMPOSE_PROJECT_NAME GEUNEUL_BIND_ADDRESS GEUNEUL_APP_PORT
  POSTGRES_ADMIN_PASSWORD DB_PASSWORD JWT_SECRET GEUNEUL_PROXY_SECRET
  S3_BUCKET_NAME S3_REGION S3_ENDPOINT S3_BROWSER_UPLOAD_PROXY_BASE_URL AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
  BACKUP_BUCKET_NAME BACKUP_RETENTION_DAYS LOCAL_BACKUP_RETENTION_DAYS
  BACKUP_AWS_ACCESS_KEY_ID BACKUP_AWS_SECRET_ACCESS_KEY
)

for key in "${required[@]}"; do
  count="$(grep -c "^${key}=" "$env_file" || true)"
  [[ "$count" == "1" ]] || fail "${key} must appear exactly once"
  value="$(sed -n "s/^${key}=//p" "$env_file" | tr -d '\r')"
  [[ -n "$value" ]] || fail "${key} must not be empty"
  [[ "$value" != *replace-with-* ]] \
    || fail "${key} still contains a placeholder"
done

app_tag="${GEUNEUL_RELEASE_SHA:-}"
if [[ -z "$app_tag" ]]; then
  count="$(grep -c '^APP_IMAGE_TAG=' "$env_file" || true)"
  [[ "$count" == "1" ]] || fail "APP_IMAGE_TAG must appear exactly once when GEUNEUL_RELEASE_SHA is unset"
  app_tag="$(sed -n 's/^APP_IMAGE_TAG=//p' "$env_file" | tr -d '\r')"
fi
[[ "$app_tag" =~ ^[0-9a-f]{40}$ ]] || fail "APP_IMAGE_TAG must be a full lowercase Git SHA"

project_name="$(sed -n 's/^COMPOSE_PROJECT_NAME=//p' "$env_file" | tr -d '\r')"
if [[ -n "${GEUNEUL_RELEASE_SHA:-}" ]]; then
  [[ "$project_name" == "geuneul" ]] || fail "production COMPOSE_PROJECT_NAME must be geuneul"
else
  [[ "$project_name" =~ ^geuneul(-[a-z0-9-]+)?$ ]] \
    || fail "COMPOSE_PROJECT_NAME must be geuneul or an isolated geuneul-* test project"
fi

bind_address="$(sed -n 's/^GEUNEUL_BIND_ADDRESS=//p' "$env_file" | tr -d '\r')"
if [[ -n "${GEUNEUL_RELEASE_SHA:-}" ]]; then
  [[ "$bind_address" =~ ^10\.|^192\.168\.|^172\.(1[6-9]|2[0-9]|3[01])\. ]] \
    || fail "production GEUNEUL_BIND_ADDRESS must be an RFC1918 IPv4 address"
else
  [[ "$bind_address" == "127.0.0.1" \
    || "$bind_address" =~ ^10\.|^192\.168\.|^172\.(1[6-9]|2[0-9]|3[01])\. ]] \
    || fail "GEUNEUL_BIND_ADDRESS must be loopback or an RFC1918 IPv4 address"
fi

app_port="$(sed -n 's/^GEUNEUL_APP_PORT=//p' "$env_file" | tr -d '\r')"
[[ "$app_port" =~ ^[0-9]+$ ]] && (( 10#$app_port >= 1024 && 10#$app_port <= 65535 )) \
  || fail "GEUNEUL_APP_PORT must be a high TCP port"

backup_retention_days="$(sed -n 's/^BACKUP_RETENTION_DAYS=//p' "$env_file" | tr -d '\r')"
[[ "$backup_retention_days" =~ ^[0-9]+$ ]] \
  && (( 10#$backup_retention_days >= 7 && 10#$backup_retention_days <= 90 )) \
  || fail "BACKUP_RETENTION_DAYS must be between 7 and 90"
local_backup_retention_days="$(sed -n 's/^LOCAL_BACKUP_RETENTION_DAYS=//p' "$env_file" | tr -d '\r')"
[[ "$local_backup_retention_days" =~ ^[0-9]+$ ]] \
  && (( 10#$local_backup_retention_days >= 2 && 10#$local_backup_retention_days <= 14 )) \
  || fail "LOCAL_BACKUP_RETENTION_DAYS must be between 2 and 14"

endpoint="$(sed -n 's/^S3_ENDPOINT=//p' "$env_file" | tr -d '\r')"
[[ "$endpoint" =~ ^https://[a-zA-Z0-9.-]+\.compat\.objectstorage\.[a-z0-9-]+\.(oci\.customer-oci\.com|oraclecloud\.com)$ ]] \
  || fail "S3_ENDPOINT is not an OCI S3 compatibility HTTPS endpoint"
[[ "$endpoint" != https://namespace.* ]] || fail "S3_ENDPOINT still contains the example namespace"
browser_upload_proxy="$(sed -n 's/^S3_BROWSER_UPLOAD_PROXY_BASE_URL=//p' "$env_file" | tr -d '\r')"
[[ "$browser_upload_proxy" =~ ^https://[a-zA-Z0-9.-]+(:[0-9]+)?/object-storage$ ]] \
  || fail "S3_BROWSER_UPLOAD_PROXY_BASE_URL must be an exact HTTPS /object-storage base"

for secret_key in POSTGRES_ADMIN_PASSWORD DB_PASSWORD JWT_SECRET GEUNEUL_PROXY_SECRET AWS_SECRET_ACCESS_KEY BACKUP_AWS_SECRET_ACCESS_KEY; do
  value="$(sed -n "s/^${secret_key}=//p" "$env_file" | tr -d '\r')"
  [[ "${#value}" -ge 32 ]] || fail "${secret_key} must be at least 32 characters"
done

postgres_admin_password="$(sed -n 's/^POSTGRES_ADMIN_PASSWORD=//p' "$env_file" | tr -d '\r')"
database_password="$(sed -n 's/^DB_PASSWORD=//p' "$env_file" | tr -d '\r')"
jwt_secret="$(sed -n 's/^JWT_SECRET=//p' "$env_file" | tr -d '\r')"
proxy_secret="$(sed -n 's/^GEUNEUL_PROXY_SECRET=//p' "$env_file" | tr -d '\r')"
[[ "$postgres_admin_password" != "$database_password" ]] \
  || fail "PostgreSQL admin and application passwords must differ"
[[ "$jwt_secret" != "$proxy_secret" ]] || fail "JWT_SECRET and GEUNEUL_PROXY_SECRET must differ"
app_access_key="$(sed -n 's/^AWS_ACCESS_KEY_ID=//p' "$env_file" | tr -d '\r')"
backup_access_key="$(sed -n 's/^BACKUP_AWS_ACCESS_KEY_ID=//p' "$env_file" | tr -d '\r')"
app_secret_key="$(sed -n 's/^AWS_SECRET_ACCESS_KEY=//p' "$env_file" | tr -d '\r')"
backup_secret_key="$(sed -n 's/^BACKUP_AWS_SECRET_ACCESS_KEY=//p' "$env_file" | tr -d '\r')"
[[ "$app_access_key" != "$backup_access_key" && "$app_secret_key" != "$backup_secret_key" ]] \
  || fail "photo and backup Object Storage credentials must be distinct"

if docker compose version >/dev/null 2>&1; then
  compose=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  compose=(docker-compose)
else
  fail "Docker Compose v2 is unavailable"
fi

APP_IMAGE_TAG="$app_tag" GEUNEUL_ENV_FILE="$env_file" \
  "${compose[@]}" --project-directory "$oci_dir" \
    --env-file "$env_file" --file "${oci_dir}/compose.production.yml" config --quiet

printf 'OCI runtime configuration is valid.\n'
