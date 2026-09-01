#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

readonly deploy_root="/opt/marketvalley/geuneul"
readonly releases_directory="${deploy_root}/releases"
readonly shared_directory="${deploy_root}/shared"
readonly production_environment="${shared_directory}/production.env"
readonly current_link="${deploy_root}/current"
readonly previous_release_file="${shared_directory}/previous-release"
readonly deployment_lock="${shared_directory}/deployment.lock"
readonly postgis_image="geuneul-postgis:16.15-3.6.4"

incoming_directory=""
archive_path=""
deploy_uid=""
rootless_socket=""

fail() {
  printf 'geuneul release error: %s\n' "$1" >&2
  exit 1
}

is_release_sha() {
  [[ "$1" =~ ^[0-9a-f]{40}$ ]]
}

cleanup() {
  if [[ -n "$incoming_directory" && "$incoming_directory" == "${releases_directory}/."* && -d "$incoming_directory" ]]; then
    rm -rf -- "$incoming_directory"
  fi
  if [[ -n "$archive_path" && "$archive_path" == /tmp/geuneul-*.tar.gz ]]; then
    rm -f -- "$archive_path"
  fi
}
trap cleanup EXIT

configure_rootless_runtime() {
  deploy_uid="$(id -u)"
  [[ "$deploy_uid" != "0" ]] || fail "releases must run as the dedicated non-root user"
  export XDG_RUNTIME_DIR="/run/user/${deploy_uid}"
  export DBUS_SESSION_BUS_ADDRESS="unix:path=${XDG_RUNTIME_DIR}/bus"
  rootless_socket="${XDG_RUNTIME_DIR}/docker.sock"
  export DOCKER_HOST="unix://${rootless_socket}"
}

require_runtime() {
  local user_cgroup="/sys/fs/cgroup/user.slice/user-${deploy_uid}.slice/user@${deploy_uid}.service"
  for command_name in awk chmod docker find findmnt flock grep install ln mkdir mv python3 readlink rm sed seq sha256sum sleep sort stat tr; do
    command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is unavailable"
  done
  docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is unavailable"
  [[ -S "$rootless_socket" && "$(stat -c '%u' "$rootless_socket")" == "$deploy_uid" ]] \
    || fail "the rootless Docker socket is unavailable or incorrectly owned"
  [[ "$(docker info --format '{{json .SecurityOptions}}')" == *rootless* ]] \
    || fail "Docker is not running rootless"
  [[ "$(docker info --format '{{.CgroupVersion}} {{.CgroupDriver}}')" == "2 systemd" ]] \
    || fail "rootless Docker requires cgroup v2 with the systemd driver"
  [[ "$(tr -d '[:space:]' <"${user_cgroup}/cpu.max")" == "75000100000" ]] \
    || fail "deploy user CPU quota must be 75%"
  [[ "$(tr -d '[:space:]' <"${user_cgroup}/memory.max")" == "3221225472" ]] \
    || fail "deploy user memory limit must be 3 GiB"
  [[ "$(tr -d '[:space:]' <"${user_cgroup}/memory.swap.max")" == "0" ]] \
    || fail "deploy user swap must be disabled"
  [[ "$(findmnt -n -o TARGET --target /opt/marketvalley)" == "/opt/marketvalley" ]] \
    || fail "the dedicated data volume is not mounted"
  [[ "$(docker info --format '{{.DockerRootDir}}')" == "${deploy_root}/docker" ]] \
    || fail "Docker data-root is outside the Geuneul directory"
  [[ -f "$production_environment" && ! -L "$production_environment" ]] \
    || fail "production.env is missing or unsafe"
  chmod 0600 "$production_environment"
  mkdir -p "$releases_directory" "$shared_directory"
}

acquire_lock() {
  local operation="$1"
  exec 9>"$deployment_lock"
  if [[ "$operation" == "current" ]]; then
    flock --wait 1800 9 || fail "timed out waiting for an active deployment"
  else
    flock --nonblock 9 || fail "another release operation is running"
  fi
}

require_free_space_kib() {
  local minimum_kib="$1"
  local operation="$2"
  local available_kib=""
  available_kib="$(df -Pk "$deploy_root" | awk 'NR == 2 { print $4 }')"
  [[ "$available_kib" =~ ^[0-9]+$ && "$available_kib" -ge "$minimum_kib" ]] \
    || fail "$operation requires at least $((minimum_kib / 1024 / 1024)) GiB free on the shared block volume"
}

manifest_value() {
  local manifest="$1"
  local key="$2"
  [[ "$(grep -c "^${key}=" "$manifest" || true)" == "1" ]] \
    || fail "release manifest field ${key} is invalid"
  sed -n "s/^${key}=//p" "$manifest" | tr -d '\r'
}

validate_release() {
  local release_sha="$1"
  local release_directory="${releases_directory}/${release_sha}"
  local manifest="${release_directory}/release-manifest"
  local integrity="${release_directory}/.geuneul-release-integrity"
  [[ -d "$release_directory" && ! -L "$release_directory" ]] || fail "release directory is unavailable"
  for file in \
    release-manifest \
    backend-image.tar \
    postgis-image.tar \
    infra/oci/compose.production.yml \
    infra/oci/scripts/validate-runtime.sh; do
    [[ -f "${release_directory}/${file}" && ! -L "${release_directory}/${file}" ]] \
      || fail "release file ${file} is missing or unsafe"
  done
  [[ -f "$integrity" && ! -L "$integrity" ]] || fail "trusted release integrity record is missing"
  [[ "$(manifest_value "$manifest" source_sha)" == "$release_sha" ]] \
    || fail "release manifest source SHA does not match"
  [[ "$(readlink -f "$release_directory")" == "$(readlink -f "$releases_directory")/${release_sha}" ]] \
    || fail "release directory escapes the managed root"
}

extract_release() {
  local release_sha="$1"
  local archive_digest="$2"
  local release_directory="${releases_directory}/${release_sha}"
  local integrity="${release_directory}/.geuneul-release-integrity"
  if [[ -d "$release_directory" && ! -L "$release_directory" ]]; then
    [[ -f "$integrity" ]] || fail "existing release has no integrity record"
    grep -Fqx "archive_sha256=${archive_digest}" "$integrity" \
      || fail "the same source SHA was supplied with a different archive"
    validate_release "$release_sha"
    return
  fi
  [[ ! -e "$release_directory" && ! -L "$release_directory" ]] || fail "release path is unsafe"
  incoming_directory="${releases_directory}/.${release_sha}.incoming.$$"
  mkdir "$incoming_directory"
  python3 /usr/local/lib/geuneul/validate-release-archive.py "$archive_path" "$incoming_directory"
  for file in \
    release-manifest \
    backend-image.tar \
    postgis-image.tar \
    infra/oci/compose.production.yml \
    infra/oci/scripts/validate-runtime.sh; do
    [[ -f "${incoming_directory}/${file}" && ! -L "${incoming_directory}/${file}" ]] \
      || fail "release file ${file} is missing or unsafe"
  done
  [[ "$(manifest_value "${incoming_directory}/release-manifest" source_sha)" == "$release_sha" ]] \
    || fail "release manifest source SHA does not match"
  printf 'archive_sha256=%s\n' "$archive_digest" >"${incoming_directory}/.geuneul-release-integrity"
  chmod 0600 "${incoming_directory}/.geuneul-release-integrity"
  mv "$incoming_directory" "$release_directory"
  incoming_directory=""
  validate_release "$release_sha"
}

load_release_images() {
  local release_sha="$1"
  local release_directory="${releases_directory}/${release_sha}"
  local manifest="${release_directory}/release-manifest"
  local backend_digest=""
  local postgis_digest=""
  backend_digest="$(manifest_value "$manifest" backend_archive_sha256)"
  postgis_digest="$(manifest_value "$manifest" postgis_archive_sha256)"
  [[ "$backend_digest" =~ ^[0-9a-f]{64}$ && "$postgis_digest" =~ ^[0-9a-f]{64}$ ]] \
    || fail "release image digest is invalid"
  printf '%s  %s\n' "$backend_digest" "${release_directory}/backend-image.tar" \
    | sha256sum --check --status || fail "backend image archive checksum failed"
  printf '%s  %s\n' "$postgis_digest" "${release_directory}/postgis-image.tar" \
    | sha256sum --check --status || fail "PostGIS image archive checksum failed"
  docker load --input "${release_directory}/backend-image.tar" >/dev/null
  docker load --input "${release_directory}/postgis-image.tar" >/dev/null
  [[ "$(docker image inspect --format '{{.Architecture}}' "geuneul-backend:${release_sha}")" == "arm64" ]] \
    || fail "backend image is not arm64"
  [[ "$(docker image inspect --format '{{index .Config.Labels \"org.opencontainers.image.revision\"}}' "geuneul-backend:${release_sha}")" == "$release_sha" ]] \
    || fail "backend image revision label does not match"
  [[ "$(docker image inspect --format '{{.Architecture}}' "$postgis_image")" == "arm64" ]] \
    || fail "PostGIS image is not arm64"
}

read_current_release() {
  local target=""
  if [[ -L "$current_link" ]]; then
    target="$(readlink "$current_link")"
    target="${target##*/}"
    is_release_sha "$target" || fail "current release link is invalid"
    printf '%s' "$target"
  fi
}

set_current_release() {
  local release_sha="$1"
  local temporary_link="${deploy_root}/.current.${release_sha}.$$"
  ln -s "releases/${release_sha}" "$temporary_link"
  mv -Tf "$temporary_link" "$current_link"
}

write_previous_release() {
  local release_sha="$1"
  local temporary_file="${shared_directory}/.previous-release.$$"
  printf '%s\n' "$release_sha" >"$temporary_file"
  mv -f "$temporary_file" "$previous_release_file"
}

compose() {
  local release_sha="$1"
  shift
  APP_IMAGE_TAG="$release_sha" GEUNEUL_ENV_FILE="$production_environment" \
    docker compose \
      --project-name geuneul \
      --env-file "$production_environment" \
      --file "${releases_directory}/${release_sha}/infra/oci/compose.production.yml" \
      "$@"
}

wait_for_healthy_app() {
  local release_sha="$1"
  local container_id=""
  local status=""
  for _ in $(seq 1 90); do
    container_id="$(compose "$release_sha" ps --quiet app 2>/dev/null || true)"
    if [[ -n "$container_id" ]]; then
      status="$(docker inspect --format '{{.State.Health.Status}}' "$container_id" 2>/dev/null || true)"
      [[ "$status" == "healthy" ]] && return
      [[ "$status" != "unhealthy" ]] || return 1
    fi
    sleep 2
  done
  return 1
}

wait_for_healthy_data_services() {
  local release_sha="$1"
  local service=""
  local container_id=""
  local status=""
  for service in postgres redis; do
    for _ in $(seq 1 60); do
      container_id="$(compose "$release_sha" ps --quiet "$service" 2>/dev/null || true)"
      if [[ -n "$container_id" ]]; then
        status="$(docker inspect --format '{{.State.Health.Status}}' "$container_id" 2>/dev/null || true)"
        [[ "$status" == "healthy" ]] && break
        [[ "$status" != "unhealthy" ]] || return 1
      fi
      sleep 2
    done
    [[ "$status" == "healthy" ]] || return 1
  done
}

start_data_services() {
  local release_sha="$1"
  require_free_space_kib $((10 * 1024 * 1024)) "starting data services"
  validate_release "$release_sha"
  load_release_images "$release_sha"
  GEUNEUL_RELEASE_SHA="$release_sha" \
    "${releases_directory}/${release_sha}/infra/oci/scripts/validate-runtime.sh" "$production_environment"
  compose "$release_sha" up --detach --no-build postgres redis
  wait_for_healthy_data_services "$release_sha"
}

activate_release() {
  local release_sha="$1"
  local previous_sha=""
  require_free_space_kib $((8 * 1024 * 1024)) "release activation"
  validate_release "$release_sha"
  load_release_images "$release_sha"
  GEUNEUL_RELEASE_SHA="$release_sha" \
    "${releases_directory}/${release_sha}/infra/oci/scripts/validate-runtime.sh" "$production_environment"
  previous_sha="$(read_current_release)"
  compose "$release_sha" up --detach --no-build postgres redis app
  wait_for_healthy_app "$release_sha" || return 1
  if [[ -n "$previous_sha" && "$previous_sha" != "$release_sha" ]]; then
    write_previous_release "$previous_sha"
  fi
  set_current_release "$release_sha"
  systemctl --user enable --now geuneul-backup.timer geuneul-health.timer >/dev/null
  if [[ ! -f "${deploy_root}/backups/last-success" ]]; then
    systemctl --user start geuneul-backup.service
  fi
}

stage_release() {
  local release_sha="${GEUNEUL_RELEASE_SHA:-}"
  local archive_digest="${GEUNEUL_ARCHIVE_SHA256:-}"
  is_release_sha "$release_sha" || fail "GEUNEUL_RELEASE_SHA must be a full Git SHA"
  [[ "$archive_digest" =~ ^[0-9a-f]{64}$ ]] || fail "archive digest is invalid"
  archive_path="${GEUNEUL_ARCHIVE_PATH:-}"
  [[ "$archive_path" == "/tmp/geuneul-${release_sha}.tar.gz" ]] \
    || fail "release archive path is invalid"
  [[ -f "$archive_path" && ! -L "$archive_path" ]] || fail "release archive is missing or unsafe"
  printf '%s  %s\n' "$archive_digest" "$archive_path" | sha256sum --check --status \
    || fail "release archive checksum failed"
  prune_old_releases "$release_sha" "$(read_current_release)"
  require_free_space_kib $((12 * 1024 * 1024)) "release staging"
  extract_release "$release_sha" "$archive_digest"
  load_release_images "$release_sha"
  printf 'geuneul release %s is staged.\n' "$release_sha"
}

prune_old_releases() {
  local active_sha="$1"
  local rollback_sha="$2"
  local index=0
  local release_sha=""
  local -a release_shas=()
  local -A keep=(["$active_sha"]=1)
  is_release_sha "$rollback_sha" && keep["$rollback_sha"]=1
  mapfile -t release_shas < <(
    find "$releases_directory" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %f\n' \
      | sort -rn | awk '{print $2}'
  )
  for release_sha in "${release_shas[@]}"; do
    is_release_sha "$release_sha" || continue
    if (( index < 5 )); then keep["$release_sha"]=1; fi
    (( index += 1 ))
  done
  for release_sha in "${release_shas[@]}"; do
    is_release_sha "$release_sha" || continue
    [[ -z "${keep[${release_sha}]:-}" ]] || continue
    rm -rf -- "${releases_directory:?}/${release_sha}"
    docker image rm "geuneul-backend:${release_sha}" >/dev/null 2>&1 || true
  done
}

operation="${1:-}"
configure_rootless_runtime
require_runtime
acquire_lock "$operation"

case "$operation" in
  current)
    read_current_release
    printf '\n'
    ;;
  stage)
    stage_release
    ;;
  start-data)
    target_sha="${GEUNEUL_TARGET_SHA:-}"
    is_release_sha "$target_sha" || fail "data service release SHA is invalid"
    start_data_services "$target_sha" || fail "data services did not become healthy"
    printf 'geuneul data services for release %s are healthy; the app remains stopped.\n' "$target_sha"
    ;;
  deploy)
    stage_release
    target_sha="${GEUNEUL_RELEASE_SHA}"
    previous_sha="$(read_current_release)"
    if ! activate_release "$target_sha"; then
      if is_release_sha "$previous_sha" && activate_release "$previous_sha"; then
        printf 'Activation failed; automatic rollback restored %s.\n' "$previous_sha" >&2
      fi
      fail "release activation failed"
    fi
    prune_old_releases "$target_sha" "$previous_sha"
    printf 'geuneul release %s is healthy.\n' "$target_sha"
    ;;
  activate)
    target_sha="${GEUNEUL_TARGET_SHA:-}"
    is_release_sha "$target_sha" || fail "activation SHA is invalid"
    activate_release "$target_sha" || fail "release activation failed"
    printf 'geuneul release %s is healthy.\n' "$target_sha"
    ;;
  rollback)
    target_sha="${GEUNEUL_TARGET_SHA:-}"
    is_release_sha "$target_sha" || fail "rollback SHA is invalid"
    current_sha="$(read_current_release)"
    activate_release "$target_sha" || fail "rollback release did not become healthy"
    if is_release_sha "$current_sha"; then write_previous_release "$current_sha"; fi
    printf 'geuneul rollback restored %s.\n' "$target_sha"
    ;;
  *) fail "usage: remote-release.sh [current|stage|start-data|activate|deploy|rollback]" ;;
esac
