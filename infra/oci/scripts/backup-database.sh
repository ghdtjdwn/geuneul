#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly oci_dir="$(cd -- "${script_dir}/.." && pwd)"
readonly env_file="${1:-}"
readonly backup_root="${2:-}"
readonly aws_cli_image="public.ecr.aws/aws-cli/aws-cli:2.31.30@sha256:6c9314d8dd18bcfd11c509e00ea39016c4fb978e42e86df1e4dfd17038b10b78"

source "${script_dir}/runtime-lib.sh"

fail() {
  printf 'Database backup failed: %s\n' "$1" >&2
  exit 1
}

[[ -n "$env_file" && -n "$backup_root" ]] \
  || fail "usage: backup-database.sh /absolute/path/production.env /absolute/backup/directory"
[[ "$backup_root" == /* ]] || fail "backup directory path must be absolute"
[[ ! -L "$backup_root" ]] || fail "backup directory must not be a symlink"
install -d -m 0700 "$backup_root"
exec 9>"${backup_root}/.backup.lock"
flock --nonblock 9 || fail "another database backup is already running"

"${script_dir}/validate-runtime.sh" "$env_file"
resolve_compose

database_size="$("${COMPOSE[@]}" --env-file "$env_file" --file "${oci_dir}/compose.production.yml" \
  exec -T postgres psql --username geuneul_app --dbname geuneul \
  --no-align --tuples-only --set ON_ERROR_STOP=1 --command 'SELECT pg_database_size(current_database());')"
[[ "$database_size" =~ ^[0-9]+$ ]] || fail "database size preflight returned an invalid value"
available_kilobytes="$(df -Pk "$backup_root" | awk 'NR == 2 { print $4 }')"
[[ "$available_kilobytes" =~ ^[0-9]+$ ]] || fail "backup filesystem free space is unavailable"
available_bytes=$((available_kilobytes * 1024))
required_bytes=$((database_size * 2 + 2 * 1024 * 1024 * 1024))
(( available_bytes >= required_bytes )) \
  || fail "backup filesystem needs at least twice the database size plus 2 GiB free"

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
dump_name="geuneul-${timestamp}.dump"
dump_path="${backup_root}/${dump_name}"
partial_path="${dump_path}.partial"
counts_path="${backup_root}/geuneul-${timestamp}.table-counts.tsv"
checksum_path="${dump_path}.sha256"
aws_env="$(mktemp "${backup_root}/.aws-env.XXXXXX")"
cleanup() { rm -f -- "$partial_path" "$aws_env"; }
trap cleanup EXIT

GEUNEUL_ENV_FILE="$env_file" "${COMPOSE[@]}" \
  --env-file "$env_file" --file "${oci_dir}/compose.production.yml" \
  exec -T postgres pg_dump \
    --username geuneul_app --dbname geuneul \
    --format custom --compress 9 --no-owner --no-acl >"$partial_path"

[[ -s "$partial_path" ]] || fail "pg_dump produced an empty archive"
GEUNEUL_ENV_FILE="$env_file" "${COMPOSE[@]}" \
  --env-file "$env_file" --file "${oci_dir}/compose.production.yml" \
  exec -T postgres pg_restore --list <"$partial_path" >/dev/null
mv -- "$partial_path" "$dump_path"
(
  cd -- "$backup_root"
  sha256sum "$dump_name" >"$(basename "$checksum_path")"
)

GEUNEUL_ENV_FILE="$env_file" "${COMPOSE[@]}" \
  --env-file "$env_file" --file "${oci_dir}/compose.production.yml" \
  exec -T postgres psql --username geuneul_app --dbname geuneul \
    --no-align --tuples-only --field-separator $'\t' --set ON_ERROR_STOP=1 <<'SQL' >"$counts_path"
SELECT format(
  'SELECT %L AS table_name, count(*) AS row_count FROM %I.%I;',
  table_name, table_schema, table_name
)
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY table_name
\gexec
SQL

endpoint="$(read_env_value "$env_file" S3_ENDPOINT)"
region="$(read_env_value "$env_file" S3_REGION)"
bucket="$(read_env_value "$env_file" BACKUP_BUCKET_NAME)"
access_key="$(read_env_value "$env_file" BACKUP_AWS_ACCESS_KEY_ID)"
secret_key="$(read_env_value "$env_file" BACKUP_AWS_SECRET_ACCESS_KEY)"
printf 'AWS_ACCESS_KEY_ID=%s\nAWS_SECRET_ACCESS_KEY=%s\n' "$access_key" "$secret_key" >"$aws_env"
chmod 600 "$aws_env"
remote_prefix="database/${timestamp:0:4}/${timestamp:4:2}"

for path in "$dump_path" "$checksum_path" "$counts_path"; do
  docker run --rm \
    --env-file "$aws_env" \
    --env AWS_DEFAULT_REGION="$region" \
    --env AWS_EC2_METADATA_DISABLED=true \
    --volume "${backup_root}:/backups:ro" \
    "$aws_cli_image" \
    --endpoint-url "$endpoint" s3 cp "/backups/$(basename "$path")" \
    "s3://${bucket}/${remote_prefix}/$(basename "$path")" --only-show-errors
done

local_size="$(portable_file_size "$dump_path")"
remote_size="$(
  docker run --rm --env-file "$aws_env" \
    --env AWS_DEFAULT_REGION="$region" --env AWS_EC2_METADATA_DISABLED=true \
    "$aws_cli_image" --endpoint-url "$endpoint" s3api head-object \
    --bucket "$bucket" --key "${remote_prefix}/${dump_name}" --query ContentLength --output text
)"
[[ "$remote_size" == "$local_size" ]] || fail "off-host archive size does not match the local archive"

status_file="${backup_root}/last-success"
status_partial="${status_file}.partial"
printf '%s\t%s\t%s\t%s\n' "$(date -u +%s)" "$dump_name" "$local_size" "$(awk '{ print $1 }' "$checksum_path")" \
  >"$status_partial"
mv -- "$status_partial" "$status_file"

local_retention_days="$(read_env_value "$env_file" LOCAL_BACKUP_RETENTION_DAYS)"
find "$backup_root" -maxdepth 1 -type f \
  \( -name 'geuneul-*.dump' -o -name 'geuneul-*.dump.sha256' -o -name 'geuneul-*.table-counts.tsv' \) \
  -mtime "+${local_retention_days}" -delete

printf 'Database backup verified: %s (%s bytes), with checksum and table counts uploaded off-host.\n' \
  "$dump_name" "$local_size"
