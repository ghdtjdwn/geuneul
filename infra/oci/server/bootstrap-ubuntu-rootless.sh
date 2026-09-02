#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

readonly docker_engine_version="29.7.2"
readonly docker_cli_package_version="5:29.7.2-1~ubuntu.22.04~jammy"
readonly docker_buildx_package_version="0.36.1-1~ubuntu.22.04~jammy"
readonly docker_compose_package_version="5.5.0-1~ubuntu.22.04~jammy"
readonly docker_rootless_package_version="5:29.7.2-1~ubuntu.22.04~jammy"
readonly docker_archive_sha256="43d143448adf2c2787704e7d7704fd6d62d367a54c5edaef0a3f75509cb0938d"
readonly docker_gpg_sha256="1500c1f56fa9e26b9b8f42452a553675796ade0807cdce11975eb98170b3a570"
readonly data_mount="/opt/marketvalley"
readonly deploy_root="${data_mount}/geuneul"

temporary_directory=""

fail() {
  printf 'geuneul rootless bootstrap error: %s\n' "$1" >&2
  exit 1
}

cleanup() {
  if [[ -n "$temporary_directory" && "$temporary_directory" == /tmp/geuneul-rootless.* ]]; then
    rm -rf -- "$temporary_directory"
  fi
}
trap cleanup EXIT

[[ "$(id -u)" -eq 0 ]] || fail "run this script as root"
[[ -r /etc/os-release ]] || fail "/etc/os-release is missing"
# shellcheck disable=SC1091
. /etc/os-release
[[ "${ID:-}" == "ubuntu" && "${VERSION_ID:-}" == "22.04" ]] \
  || fail "this pinned bootstrap supports Ubuntu 22.04 only"
[[ "$(dpkg --print-architecture)" == "arm64" ]] \
  || fail "this pinned bootstrap supports the OCI Ampere A1 arm64 host only"
script_directory="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
. "${script_directory}/verify-host-storage.sh"
geuneul_verify_host_storage
geuneul_require_host_capacity

deploy_user="${GEUNEUL_DEPLOY_USER:-geuneul}"
public_key_file="${GEUNEUL_DEPLOY_PUBLIC_KEY_FILE:-}"
deploy_marker="/etc/geuneul-deploy-user"
[[ "$deploy_user" =~ ^[a-z_][a-z0-9_-]*$ ]] || fail "deploy user name is invalid"
[[ -n "$public_key_file" && "$public_key_file" == /* ]] \
  || fail "GEUNEUL_DEPLOY_PUBLIC_KEY_FILE must be an absolute path"
[[ -f "$public_key_file" && ! -L "$public_key_file" ]] || fail "deploy public key file is missing or unsafe"
ssh-keygen -l -f "$public_key_file" >/dev/null || fail "deploy public key is invalid"
[[ "$(awk 'NF { count += 1; type = $1; fields = NF } END { print count ":" type ":" fields }' "$public_key_file")" == "1:ssh-ed25519:3" ]] \
  || fail "deploy public key must contain exactly one Ed25519 public key"
deploy_public_key="$(<"$public_key_file")"
restricted_authorized_key="restrict,command=\"/usr/local/lib/geuneul/deploy-gateway.sh\" ${deploy_public_key}"

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install --yes acl ca-certificates curl dbus-user-session fuse-overlayfs python3-minimal uidmap util-linux slirp4netns
python3 -c 'import sys; raise SystemExit(sys.version_info < (3, 10))' \
  || fail "Python 3.10 or newer is required"

temporary_directory="$(mktemp -d /tmp/geuneul-rootless.XXXXXX)"
curl --fail --location --proto '=https' --tlsv1.2 \
  https://download.docker.com/linux/ubuntu/gpg \
  --output "${temporary_directory}/docker.asc"
printf '%s  %s\n' "$docker_gpg_sha256" "${temporary_directory}/docker.asc" \
  | sha256sum --check --status || fail "Docker apt signing key checksum changed"
install -d -m 0755 /etc/apt/keyrings
install -m 0644 "${temporary_directory}/docker.asc" /etc/apt/keyrings/docker.asc
printf '%s\n' \
  'Types: deb' \
  'URIs: https://download.docker.com/linux/ubuntu' \
  'Suites: jammy' \
  'Components: stable' \
  'Architectures: arm64' \
  'Signed-By: /etc/apt/keyrings/docker.asc' \
  >"${temporary_directory}/docker.sources"
install -m 0644 "${temporary_directory}/docker.sources" /etc/apt/sources.list.d/docker.sources
apt-get update
apt-get install --yes \
  "docker-ce-cli=${docker_cli_package_version}" \
  "docker-buildx-plugin=${docker_buildx_package_version}" \
  "docker-compose-plugin=${docker_compose_package_version}" \
  "docker-ce-rootless-extras=${docker_rootless_package_version}"

if id "$deploy_user" >/dev/null 2>&1; then
  [[ -f "$deploy_marker" && ! -L "$deploy_marker" && "$(<"$deploy_marker")" == "$deploy_user" ]] \
    || fail "refusing an unmarked existing deploy user"
else
  [[ ! -e "$deploy_marker" && ! -L "$deploy_marker" ]] || fail "deploy user marker already exists"
  useradd --create-home --shell /bin/bash "$deploy_user"
  install -m 0644 /dev/null "$deploy_marker"
  printf '%s\n' "$deploy_user" >"$deploy_marker"
fi

deploy_uid="$(id -u "$deploy_user")"
deploy_home="$(getent passwd "$deploy_user" | awk -F: '{print $6}')"
[[ "$deploy_uid" != "0" && -d "$deploy_home" && ! -L "$deploy_home" ]] \
  || fail "deploy user home is missing or unsafe"
for privileged_group in adm docker lxd libvirt root sudo systemd-journal; do
  id -nG "$deploy_user" | tr ' ' '\n' | grep -Fx "$privileged_group" >/dev/null \
    && fail "deploy user must not be in privileged group $privileged_group"
done
if grep -R -E --include='*' "(^|[[:space:]])${deploy_user}([[:space:]]|$)" /etc/sudoers /etc/sudoers.d 2>/dev/null | grep -q .; then
  fail "deploy user must not have sudo access"
fi
awk -F: -v user="$deploy_user" '$1 == user && $3 >= 65536 { found = 1 } END { exit !found }' /etc/subuid \
  || fail "deploy user needs at least 65536 subordinate UIDs"
awk -F: -v user="$deploy_user" '$1 == user && $3 >= 65536 { found = 1 } END { exit !found }' /etc/subgid \
  || fail "deploy user needs at least 65536 subordinate GIDs"

# The verified shared storage root stays owned by the other workload. Grant only path
# traversal to the dedicated Geuneul user; no directory listing or file access.
setfacl -m "u:${deploy_user}:--x" "$data_mount"
getfacl --absolute-names --omit-header "$data_mount" | grep -Fqx "user:${deploy_user}:--x" \
  || fail "the Geuneul user cannot traverse the shared storage root"
install -d -m 0750 -o "$deploy_user" -g "$deploy_user" "$deploy_root" "${deploy_root}/docker"
docker_config_directory="${deploy_home}/.config/docker"
docker_daemon_config="${docker_config_directory}/daemon.json"
install -d -m 0700 -o "$deploy_user" -g "$deploy_user" "${deploy_home}/.config" "$docker_config_directory"
if [[ -e "$docker_daemon_config" ]]; then
  [[ -f "$docker_daemon_config" && ! -L "$docker_daemon_config" ]] || fail "Docker daemon config is unsafe"
  python3 -c 'import json, sys; raise SystemExit(json.load(open(sys.argv[1])) != {"data-root": sys.argv[2]})' \
    "$docker_daemon_config" "${deploy_root}/docker" || fail "Docker daemon config is unexpected"
else
  printf '{"data-root":"%s"}\n' "${deploy_root}/docker" >"$docker_daemon_config"
  chown "$deploy_user:$deploy_user" "$docker_daemon_config"
  chmod 0600 "$docker_daemon_config"
fi

install -d -m 0700 -o "$deploy_user" -g "$deploy_user" "${deploy_home}/.ssh" "${deploy_home}/bin"
authorized_keys="${deploy_home}/.ssh/authorized_keys"
if [[ -e "$authorized_keys" ]]; then
  [[ -f "$authorized_keys" && ! -L "$authorized_keys" ]] || fail "authorized_keys is unsafe"
  [[ "$(grep -cv '^[[:space:]]*$' "$authorized_keys")" -eq 1 ]] \
    && grep -Fqx -- "$restricted_authorized_key" "$authorized_keys" \
    || fail "refusing unknown or additional deploy keys"
else
  install -m 0600 -o "$deploy_user" -g "$deploy_user" /dev/null "$authorized_keys"
  printf '%s\n' "$restricted_authorized_key" >"$authorized_keys"
fi

docker_archive="${temporary_directory}/docker-${docker_engine_version}.tgz"
curl --fail --location --proto '=https' --tlsv1.2 \
  "https://download.docker.com/linux/static/stable/aarch64/docker-${docker_engine_version}.tgz" \
  --output "$docker_archive"
printf '%s  %s\n' "$docker_archive_sha256" "$docker_archive" \
  | sha256sum --check --status || fail "Docker engine archive checksum changed"
tar -xzf "$docker_archive" -C "$temporary_directory"
for binary in containerd containerd-shim-runc-v2 ctr docker-init docker-proxy dockerd runc; do
  install -m 0755 -o "$deploy_user" -g "$deploy_user" \
    "${temporary_directory}/docker/${binary}" "${deploy_home}/bin/${binary}"
done

delegate_directory="/etc/systemd/system/user@${deploy_uid}.service.d"
install -d -m 0755 "$delegate_directory"
printf '%s\n' \
  '[Service]' \
  'Delegate=cpu cpuset io memory pids' \
  'CPUAccounting=true' \
  'MemoryAccounting=true' \
  'TasksAccounting=true' \
  'IOAccounting=true' \
  'CPUQuota=75%' \
  'MemoryMax=3G' \
  'MemorySwapMax=0' \
  'TasksMax=1024' \
  'IOWeight=100' \
  >"${temporary_directory}/delegate.conf"
install -m 0644 "${temporary_directory}/delegate.conf" "${delegate_directory}/delegate.conf"
systemctl daemon-reload
loginctl enable-linger "$deploy_user"

runtime_directory="/run/user/${deploy_uid}"
for _ in $(seq 1 20); do
  [[ -S "${runtime_directory}/bus" ]] && break
  sleep 1
done
[[ -S "${runtime_directory}/bus" ]] || fail "deploy user systemd bus did not start"

run_as_deploy() {
  runuser --user "$deploy_user" -- env \
    "DBUS_SESSION_BUS_ADDRESS=unix:path=${runtime_directory}/bus" \
    "HOME=${deploy_home}" \
    "LOGNAME=${deploy_user}" \
    "PATH=${deploy_home}/bin:/usr/local/bin:/usr/bin:/bin" \
    "USER=${deploy_user}" \
    "XDG_RUNTIME_DIR=${runtime_directory}" \
    "$@"
}

run_as_deploy dockerd-rootless-setuptool.sh install --force
install -d -m 0755 /usr/local/lib/geuneul
install -m 0644 -o root -g root "${script_directory}/verify-host-storage.sh" \
  /usr/local/lib/geuneul/verify-host-storage.sh
install -m 0755 -o root -g root "${script_directory}/verify-storage-start.sh" \
  /usr/local/lib/geuneul/verify-storage-start.sh
install -d -m 0755 -o "$deploy_user" -g "$deploy_user" "${deploy_home}/.config/systemd/user/docker.service.d"
printf '%s\n' \
  '[Unit]' \
  'ConditionPathIsMountPoint=/opt/marketvalley' \
  '[Service]' \
  'ExecStartPre=/usr/local/lib/geuneul/verify-storage-start.sh' \
  >"${deploy_home}/.config/systemd/user/docker.service.d/geuneul-data.conf"
chown "$deploy_user:$deploy_user" "${deploy_home}/.config/systemd/user/docker.service.d/geuneul-data.conf"
run_as_deploy systemctl --user daemon-reload
run_as_deploy systemctl --user enable --now docker.service
[[ "$(run_as_deploy docker info --format '{{.DockerRootDir}}')" == "${deploy_root}/docker" ]] \
  || fail "rootless Docker data-root is outside the verified shared storage"

for script in check-production-health.sh deploy-gateway.sh release-manager.sh remote-release.sh validate-release-archive.py; do
  install -m 0755 -o root -g root "${script_directory}/${script}" "/usr/local/lib/geuneul/${script}"
done
GEUNEUL_DEPLOY_USER="$deploy_user" bash "${script_directory}/prepare-host.sh"
install -d -m 0755 -o "$deploy_user" -g "$deploy_user" "${deploy_home}/.config/systemd/user"
for unit in geuneul-backup.service geuneul-backup.timer geuneul-health.service geuneul-health.timer; do
  install -m 0644 -o "$deploy_user" -g "$deploy_user" \
    "${script_directory}/systemd/${unit}" "${deploy_home}/.config/systemd/user/${unit}"
done
run_as_deploy systemctl --user daemon-reload

printf 'Pinned rootless Docker %s is ready for %s.\n' "$docker_engine_version" "$deploy_user"
