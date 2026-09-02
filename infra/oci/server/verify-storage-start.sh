#!/usr/bin/env bash
set -Eeuo pipefail

fail() {
  printf 'geuneul storage start check failed: %s\n' "$1" >&2
  exit 1
}

readonly storage_library="/usr/local/lib/geuneul/verify-host-storage.sh"
[[ -f "${storage_library}" && ! -L "${storage_library}" ]] \
  || fail "trusted host storage verifier is unavailable"
[[ "$(stat -c '%u:%g:%a' "${storage_library}")" == "0:0:644" ]] \
  || fail "trusted host storage verifier must be root-owned mode 0644"
# shellcheck disable=SC1091
. "${storage_library}"
geuneul_verify_host_storage
