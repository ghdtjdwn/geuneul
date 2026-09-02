#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly source_env="${1:-}"
readonly target_env="${2:-}"
readonly staging_root="${3:-}"
readonly aws_cli_image="public.ecr.aws/aws-cli/aws-cli:2.31.30@sha256:6c9314d8dd18bcfd11c509e00ea39016c4fb978e42e86df1e4dfd17038b10b78"
readonly source_excluded_prefix="migration/database/"

source "${script_dir}/runtime-lib.sh"

fail() {
  printf 'Object migration refused: %s\n' "$1" >&2
  exit 1
}

[[ "${OBJECT_MIGRATION_CONFIRM:-}" == "MIGRATE_GEUNEUL_OBJECTS" ]] \
  || fail "set OBJECT_MIGRATION_CONFIRM=MIGRATE_GEUNEUL_OBJECTS after reviewing both buckets"
[[ -n "$source_env" && -n "$target_env" && -n "$staging_root" ]] \
  || fail "usage: migrate-objects.sh AWS.env OCI.env /absolute/empty/staging-directory"
require_regular_0600_file "$source_env" "AWS source environment"
require_regular_0600_file "$target_env" "OCI target environment"
[[ "$staging_root" == /* ]] || fail "staging directory path must be absolute"
[[ ! -e "$staging_root" ]] || fail "staging directory must not already exist"
install -d -m 0700 "$staging_root/source" "$staging_root/target-verify" "$staging_root/inventory"

source_bucket="$(read_env_value "$source_env" SOURCE_BUCKET_NAME)"
source_region="$(read_env_value "$source_env" AWS_DEFAULT_REGION)"
target_bucket="$(read_env_value "$target_env" TARGET_BUCKET_NAME)"
target_region="$(read_env_value "$target_env" AWS_DEFAULT_REGION)"
target_endpoint="$(read_env_value "$target_env" S3_ENDPOINT)"
target_namespace="$(read_env_value "$target_env" TARGET_NAMESPACE)"
source_access_key="$(read_env_value "$source_env" AWS_ACCESS_KEY_ID)"
source_secret_key="$(read_env_value "$source_env" AWS_SECRET_ACCESS_KEY)"
target_access_key="$(read_env_value "$target_env" AWS_ACCESS_KEY_ID)"
target_secret_key="$(read_env_value "$target_env" AWS_SECRET_ACCESS_KEY)"

[[ "$source_bucket" =~ ^[a-zA-Z0-9._-]+$ && "$target_bucket" =~ ^[a-zA-Z0-9._-]+$ ]] \
  || fail "source and target bucket names are invalid"
[[ "$source_region" =~ ^[a-z0-9-]+$ && "$target_region" =~ ^[a-z0-9-]+$ ]] \
  || fail "source and target regions are invalid"
[[ "$target_namespace" =~ ^[a-zA-Z0-9_-]+$ ]] || fail "target OCI namespace is invalid"
[[ "$target_endpoint" == "https://${target_namespace}.compat.objectstorage.${target_region}.oci.customer-oci.com" \
  || "$target_endpoint" == "https://${target_namespace}.compat.objectstorage.${target_region}.oraclecloud.com" ]] \
  || fail "target endpoint must exactly match the declared OCI namespace and region"
[[ "$source_access_key" != "$target_access_key" && "$source_secret_key" != "$target_secret_key" ]] \
  || fail "AWS source and OCI target credentials must be distinct"
command -v jq >/dev/null 2>&1 || fail "jq is unavailable"

aws_cli() {
  local env_path="$1"
  local region="$2"
  shift 2
  docker run --rm --env-file "$env_path" \
    --env AWS_DEFAULT_REGION="$region" --env AWS_EC2_METADATA_DISABLED=true \
    --env AWS_REQUEST_CHECKSUM_CALCULATION=WHEN_REQUIRED \
    --env AWS_RESPONSE_CHECKSUM_VALIDATION=WHEN_REQUIRED \
    --volume "${staging_root}:/work" "$aws_cli_image" "$@"
}

target_aws_cli() {
  local attempt=1
  local max_attempts=20
  local error_file="${staging_root}/inventory/target-request-error.log"

  while ! aws_cli "$target_env" "$target_region" "$@" 2>"$error_file"; do
    cat "$error_file" >&2
    if ! grep -Eq 'SignatureDoesNotMatch|RequestTimeout|InternalError|ServiceUnavailable|Connection (reset|timed out)' "$error_file"; then
      return 1
    fi
    if (( attempt >= max_attempts )); then
      fail "OCI S3 compatibility request failed after ${max_attempts} attempts"
    fi
    printf 'OCI S3 compatibility request failed; retrying after credential propagation (%d/%d).\n' \
      "$attempt" "$max_attempts" >&2
    attempt=$((attempt + 1))
    sleep 3
  done
  rm -f "$error_file"
}

aws_cli "$source_env" "$source_region" s3api list-objects-v2 --bucket "$source_bucket" \
  --query 'Contents[].{Key:Key,Size:Size}' --output json >"${staging_root}/inventory/source-all-s3.json"
jq --arg prefix "$source_excluded_prefix" \
  '[.[]? | select(.Key | startswith($prefix) | not)]' \
  "${staging_root}/inventory/source-all-s3.json" >"${staging_root}/inventory/source-s3.json"
excluded_source_count="$(
  jq --arg prefix "$source_excluded_prefix" \
    '[.[]? | select(.Key | startswith($prefix))] | length' \
    "${staging_root}/inventory/source-all-s3.json"
)"
excluded_source_bytes="$(
  jq --arg prefix "$source_excluded_prefix" \
    '[.[]? | select(.Key | startswith($prefix)) | .Size] | add // 0' \
    "${staging_root}/inventory/source-all-s3.json"
)"
[[ "$excluded_source_count" =~ ^[0-9]+$ && "$excluded_source_bytes" =~ ^[0-9]+$ ]] \
  || fail "excluded source inventory is invalid"

target_aws_cli --endpoint-url "$target_endpoint" s3api list-objects-v2 \
  --bucket "$target_bucket" --query 'Contents[].{Key:Key,Size:Size}' --output json \
  >"${staging_root}/inventory/target-before.json"
target_before_count="$(jq 'length // 0' "${staging_root}/inventory/target-before.json")"
[[ "$target_before_count" =~ ^[0-9]+$ ]] || fail "target inventory count is invalid"
if (( target_before_count > 0 )); then
  [[ "${OBJECT_MIGRATION_RESUME_CONFIRM:-}" == "RESUME_VERIFIED_GEUNEUL_OBJECTS" ]] \
    || fail "target bucket is not empty; set OBJECT_MIGRATION_RESUME_CONFIRM=RESUME_VERIFIED_GEUNEUL_OBJECTS only for a reviewed resume"
fi

jq -n \
  --arg source_provider aws \
  --arg source_bucket "$source_bucket" \
  --arg source_region "$source_region" \
  --arg target_provider oci \
  --arg target_namespace "$target_namespace" \
  --arg target_bucket "$target_bucket" \
  --arg target_region "$target_region" \
  --arg target_endpoint "$target_endpoint" \
  --arg source_excluded_prefix "$source_excluded_prefix" \
  --argjson excluded_source_count "$excluded_source_count" \
  --argjson excluded_source_bytes "$excluded_source_bytes" \
  --argjson target_initial_object_count "$target_before_count" \
  '{source: {provider: $source_provider, bucket: $source_bucket, region: $source_region,
      excludedPrefix: $source_excluded_prefix, excludedObjectCount: $excluded_source_count,
      excludedBytes: $excluded_source_bytes},
    target: {provider: $target_provider, namespace: $target_namespace, bucket: $target_bucket,
      region: $target_region, endpoint: $target_endpoint, initialObjectCount: $target_initial_object_count}}' \
  >"${staging_root}/inventory/migration-context.json"

aws_cli "$source_env" "$source_region" s3 sync "s3://${source_bucket}" /work/source \
  --exclude "${source_excluded_prefix}*" --no-follow-symlinks --only-show-errors
python3 "${script_dir}/object-inventory.py" "${staging_root}/source" \
  "${staging_root}/inventory/source-local.json"

target_aws_cli --endpoint-url "$target_endpoint" s3 sync \
  /work/source "s3://${target_bucket}" --no-follow-symlinks --only-show-errors
target_aws_cli --endpoint-url "$target_endpoint" s3api list-objects-v2 \
  --bucket "$target_bucket" --query 'Contents[].{Key:Key,Size:Size}' --output json \
  >"${staging_root}/inventory/target-s3.json"
target_aws_cli --endpoint-url "$target_endpoint" s3 sync \
  "s3://${target_bucket}" /work/target-verify --no-follow-symlinks --only-show-errors
python3 "${script_dir}/object-inventory.py" "${staging_root}/target-verify" \
  "${staging_root}/inventory/target-local.json"

python3 "${script_dir}/verify-object-migration.py" \
  "${staging_root}/inventory/source-s3.json" \
  "${staging_root}/inventory/target-s3.json" \
  "${staging_root}/inventory/source-local.json" \
  "${staging_root}/inventory/target-local.json"

printf 'Object migration is verified. AWS source objects were not deleted. Evidence: %s/inventory\n' "$staging_root"
