#!/usr/bin/env bash
set -Eeuo pipefail

readonly deploy_root="/opt/marketvalley/geuneul"
readonly current_link="${deploy_root}/current"
readonly environment_file="${deploy_root}/shared/production.env"
readonly backup_status="${deploy_root}/backups/last-success"

fail() {
  printf 'geuneul production health failed: %s\n' "$1" >&2
  exit 1
}

[[ "$(id -u)" != "0" ]] || fail "health check must run as the rootless deploy user"
[[ -f /usr/local/lib/geuneul/verify-host-storage.sh \
  && ! -L /usr/local/lib/geuneul/verify-host-storage.sh ]] \
  || fail "trusted host storage verifier is unavailable"
[[ "$(stat -c '%u:%g:%a' /usr/local/lib/geuneul/verify-host-storage.sh)" == "0:0:644" ]] \
  || fail "trusted host storage verifier must be root-owned mode 0644"
# shellcheck disable=SC1091
. /usr/local/lib/geuneul/verify-host-storage.sh
geuneul_verify_host_storage
geuneul_require_host_capacity
[[ -L "$current_link" ]] || fail "no active release exists"
release_sha="$(readlink "$current_link")"
release_sha="${release_sha##*/}"
[[ "$release_sha" =~ ^[0-9a-f]{40}$ ]] || fail "active release SHA is invalid"
[[ -f "$environment_file" && ! -L "$environment_file" ]] || fail "production.env is unavailable"

available_kib="$(df -Pk / | awk 'NR == 2 { print $4 }')"

systemctl --user is-enabled geuneul-backup.timer >/dev/null \
  || fail "daily backup timer is not enabled"
systemctl --user is-active geuneul-backup.timer >/dev/null \
  || fail "daily backup timer is not active"
[[ -f "$backup_status" && ! -L "$backup_status" ]] || fail "no verified backup success marker exists"
read -r backup_epoch backup_name backup_size backup_digest <"$backup_status"
[[ "$backup_epoch" =~ ^[0-9]+$ && "$backup_name" == geuneul-*.dump \
  && "$backup_size" =~ ^[0-9]+$ && "$backup_digest" =~ ^[0-9a-f]{64}$ ]] \
  || fail "backup success marker is invalid"
age_seconds=$(($(date -u +%s) - backup_epoch))
(( age_seconds >= 0 && age_seconds <= 129600 )) || fail "latest verified backup is older than 36 hours"

compose_file="${current_link}/infra/oci/compose.production.yml"
container_id="$(APP_IMAGE_TAG="$release_sha" GEUNEUL_ENV_FILE="$environment_file" \
  docker compose --project-name geuneul --env-file "$environment_file" \
  --file "$compose_file" ps --quiet app)"
[[ -n "$container_id" ]] || fail "application container is unavailable"
[[ "$(docker inspect --format '{{.State.Health.Status}}' "$container_id")" == "healthy" ]] \
  || fail "application container is not healthy"
[[ "$(docker inspect --format '{{.Config.Image}}' "$container_id")" == "geuneul-backend:${release_sha}" ]] \
  || fail "application image does not match the active release"

printf 'geuneul production health is valid: release=%s backup=%s free_kib=%s\n' \
  "$release_sha" "$backup_name" "$available_kib"
