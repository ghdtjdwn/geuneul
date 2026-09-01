#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

readonly manager="/usr/local/lib/geuneul/release-manager.sh"
readonly maximum_archive_bytes=536870912
readonly gateway_lock="/opt/marketvalley/geuneul/shared/gateway.lock"

archive_path=""

fail() {
  printf 'geuneul deploy gateway: %s\n' "$1" >&2
  exit 1
}

cleanup() {
  if [[ -n "$archive_path" && "$archive_path" == /tmp/geuneul-*.tar.gz ]]; then
    rm -f -- "$archive_path"
  fi
}
trap cleanup EXIT

[[ "$(id -u)" != "0" ]] || fail "the gateway must run as the deploy user"
[[ -f "$manager" && ! -L "$manager" ]] || fail "the trusted release manager is unavailable"

original_command="${SSH_ORIGINAL_COMMAND:-}"
if [[ "$original_command" == "current" ]]; then
  exec bash "$manager" current
fi

if [[ "$original_command" =~ ^(start-data|activate|rollback)\ ([0-9a-f]{40})$ ]]; then
  export GEUNEUL_TARGET_SHA="${BASH_REMATCH[2]}"
  exec bash "$manager" "${BASH_REMATCH[1]}"
fi

if [[ "$original_command" =~ ^(stage|deploy)\ ([0-9a-f]{40})\ ([0-9a-f]{64})$ ]]; then
  operation="${BASH_REMATCH[1]}"
  release_sha="${BASH_REMATCH[2]}"
  archive_digest="${BASH_REMATCH[3]}"
  archive_path="/tmp/geuneul-${release_sha}.tar.gz"

  exec 9>"$gateway_lock"
  flock --nonblock 9 || fail "another archive upload is already running"
  install -m 0600 /dev/null "$archive_path"
  dd of="$archive_path" bs=1048576 count=513 iflag=fullblock status=none
  archive_size="$(stat -c '%s' "$archive_path")"
  [[ "$archive_size" =~ ^[0-9]+$ ]] || fail "the uploaded archive size is invalid"
  (( archive_size > 0 && archive_size <= maximum_archive_bytes )) \
    || fail "the uploaded archive exceeds the 512 MiB limit"
  printf '%s  %s\n' "$archive_digest" "$archive_path" | sha256sum --check --status \
    || fail "the uploaded archive checksum does not match"

  GEUNEUL_RELEASE_SHA="$release_sha" \
  GEUNEUL_ARCHIVE_PATH="$archive_path" \
  GEUNEUL_ARCHIVE_SHA256="$archive_digest" \
    bash "$manager" "$operation"
  exit 0
fi

fail "only current, stage/deploy <sha> <digest>, start-data/activate/rollback <sha> is allowed"
