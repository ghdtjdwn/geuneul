#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly env_file="${1:-}"
readonly marker_file="${2:-}"
readonly aws_cli_image="public.ecr.aws/aws-cli/aws-cli:2.31.30@sha256:6c9314d8dd18bcfd11c509e00ea39016c4fb978e42e86df1e4dfd17038b10b78"

source "${script_dir}/runtime-lib.sh"

fail() {
  printf 'Off-host backup verification failed: %s\n' "$1" >&2
  exit 1
}

require_regular_0600_file "$env_file" "production environment" || exit 1
require_regular_0600_file "$marker_file" "backup marker" || exit 1
[[ "$(wc -l <"$marker_file" | tr -d '[:space:]')" == "1" ]] \
  || fail "marker must contain exactly one line"

read -r backup_epoch backup_name backup_size backup_digest <"$marker_file"
[[ "$backup_epoch" =~ ^[0-9]+$ && "$backup_size" =~ ^[0-9]+$ \
  && "$backup_digest" =~ ^[0-9a-f]{64}$ ]] \
  || fail "marker fields are invalid"
[[ "$backup_name" =~ ^geuneul-([0-9]{4})([0-9]{2})[0-9]{2}T[0-9]{6}Z\.dump$ ]] \
  || fail "backup name is invalid"
(( 10#$backup_size > 0 )) || fail "backup size must be nonzero"

backup_year="${BASH_REMATCH[1]}"
backup_month="${BASH_REMATCH[2]}"
retention_days="$(read_env_value "$env_file" BACKUP_RETENTION_DAYS)"
[[ "$retention_days" =~ ^[0-9]+$ && 10#$retention_days -ge 7 && 10#$retention_days -le 90 ]] \
  || fail "BACKUP_RETENTION_DAYS must be between 7 and 90"
age_seconds=$(($(date -u +%s) - 10#$backup_epoch))
(( age_seconds >= 0 && age_seconds < 10#$retention_days * 86400 )) \
  || fail "backup marker is expired or from the future"

endpoint="$(read_env_value "$env_file" S3_ENDPOINT)"
region="$(read_env_value "$env_file" S3_REGION)"
bucket="$(read_env_value "$env_file" BACKUP_BUCKET_NAME)"
access_key="$(read_env_value "$env_file" BACKUP_AWS_ACCESS_KEY_ID)"
secret_key="$(read_env_value "$env_file" BACKUP_AWS_SECRET_ACCESS_KEY)"
remote_key="database/${backup_year}/${backup_month}/${backup_name}"
aws_env="$(mktemp)"
cleanup() { rm -f -- "$aws_env"; }
trap cleanup EXIT
printf 'AWS_ACCESS_KEY_ID=%s\nAWS_SECRET_ACCESS_KEY=%s\n' "$access_key" "$secret_key" >"$aws_env"
chmod 0600 "$aws_env"

if ! remote_size="$(
  docker run --rm --env-file "$aws_env" \
    --env AWS_DEFAULT_REGION="$region" --env AWS_EC2_METADATA_DISABLED=true \
    "$aws_cli_image" --endpoint-url "$endpoint" s3api head-object \
    --bucket "$bucket" --key "$remote_key" --query ContentLength --output text
)"; then
  fail "backup object is missing or HEAD was denied"
fi
[[ "$remote_size" =~ ^[0-9]+$ && 10#$remote_size -gt 0 && "$remote_size" == "$backup_size" ]] \
  || fail "off-host backup size does not match the marker"

if ! remote_digest="$(
  docker run --rm --env-file "$aws_env" \
    --env AWS_DEFAULT_REGION="$region" --env AWS_EC2_METADATA_DISABLED=true \
    "$aws_cli_image" --endpoint-url "$endpoint" s3 cp \
    "s3://${bucket}/${remote_key}" - --only-show-errors \
    | sha256sum | awk '{ print $1 }'
)"; then
  fail "backup object could not be downloaded for digest verification"
fi
[[ "$remote_digest" == "$backup_digest" ]] \
  || fail "off-host backup SHA-256 does not match the marker"

printf 'Off-host backup object is present, unexpired, nonempty, and SHA-256 verified.\n'
