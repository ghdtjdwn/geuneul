#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

fail() {
  printf 'geuneul host preparation error: %s\n' "$1" >&2
  exit 1
}

[[ "$(id -u)" -eq 0 ]] || fail "run this script as root"
deploy_user="${GEUNEUL_DEPLOY_USER:-}"
[[ "$deploy_user" =~ ^[a-z_][a-z0-9_-]*$ ]] || fail "GEUNEUL_DEPLOY_USER is required"
id "$deploy_user" >/dev/null 2>&1 || fail "deploy user does not exist"

deploy_uid="$(id -u "$deploy_user")"
deploy_home="$(getent passwd "$deploy_user" | awk -F: '{print $6}')"
runtime_directory="/run/user/${deploy_uid}"
rootless_socket="${runtime_directory}/docker.sock"
user_cgroup="/sys/fs/cgroup/user.slice/user-${deploy_uid}.slice/user@${deploy_uid}.service"
deploy_root="/opt/marketvalley/geuneul"

[[ "$deploy_uid" != "0" ]] || fail "deploy user must not be root"
[[ -d "$deploy_home" && ! -L "$deploy_home" ]] || fail "deploy user home is missing or unsafe"
[[ "$(findmnt -n -o FSTYPE --target /opt/marketvalley)" == "ext4" ]] \
  || fail "/opt/marketvalley must be the dedicated ext4 volume"
[[ "$(findmnt -n -o TARGET --target /opt/marketvalley)" == "/opt/marketvalley" ]] \
  || fail "/opt/marketvalley is not a mount point"
[[ -S "$rootless_socket" ]] || fail "rootless Docker socket is unavailable"
[[ "$(stat -c '%u' "$rootless_socket")" == "$deploy_uid" ]] \
  || fail "rootless Docker socket is not owned by the deploy user"
[[ -r "${user_cgroup}/cgroup.controllers" ]] || fail "deploy user cgroup is unavailable"
for controller in cpu memory pids; do
  grep -qw "$controller" "${user_cgroup}/cgroup.controllers" \
    || fail "$controller is not delegated to the deploy user"
done
[[ "$(tr -d '[:space:]' <"${user_cgroup}/cpu.max")" == "75000100000" ]] \
  || fail "deploy user aggregate CPU quota must be 75%"
[[ "$(tr -d '[:space:]' <"${user_cgroup}/memory.max")" == "3221225472" ]] \
  || fail "deploy user aggregate memory limit must be 3 GiB"
[[ "$(tr -d '[:space:]' <"${user_cgroup}/memory.swap.max")" == "0" ]] \
  || fail "deploy user swap must be disabled"

run_as_deploy() {
  runuser --user "$deploy_user" -- env \
    "DBUS_SESSION_BUS_ADDRESS=unix:path=${runtime_directory}/bus" \
    "DOCKER_HOST=unix://${rootless_socket}" \
    "HOME=${deploy_home}" \
    "LOGNAME=${deploy_user}" \
    "PATH=${deploy_home}/bin:/usr/local/bin:/usr/bin:/bin" \
    "USER=${deploy_user}" \
    "XDG_RUNTIME_DIR=${runtime_directory}" \
    "$@"
}

run_as_deploy systemctl --user is-active docker.service >/dev/null \
  || fail "rootless docker.service is not active"
[[ "$(run_as_deploy docker info --format '{{.DockerRootDir}}')" == "${deploy_root}/docker" ]] \
  || fail "rootless Docker data-root is outside the Geuneul directory"

script_directory="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
template_path="${script_directory}/../production.env.example"
environment_path="${deploy_root}/shared/production.env"
[[ -f "$template_path" ]] || fail "production.env.example is missing"

install -d -m 0750 -o "$deploy_user" -g "$deploy_user" \
  "$deploy_root" "${deploy_root}/docker" "${deploy_root}/releases" "${deploy_root}/shared"
install -d -m 0700 -o "$deploy_user" -g "$deploy_user" "${deploy_root}/backups"
if [[ ! -e "$environment_path" ]]; then
  install -m 0600 -o "$deploy_user" -g "$deploy_user" "$template_path" "$environment_path"
  printf 'Created %s. Replace every placeholder before staging production.\n' "$environment_path"
else
  [[ -f "$environment_path" && ! -L "$environment_path" ]] \
    || fail "existing production.env is unsafe"
  chown "$deploy_user:$deploy_user" "$environment_path"
  chmod 0600 "$environment_path"
  printf 'Preserved existing %s.\n' "$environment_path"
fi
