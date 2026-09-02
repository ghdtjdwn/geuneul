#!/usr/bin/env bash

# Sourced by trusted server scripts. The caller must define fail().

readonly GEUNEUL_STORAGE_MARKER="/etc/marketvalley-storage-layout"
readonly GEUNEUL_STORAGE_SOURCE="/var/lib/marketvalley"
readonly GEUNEUL_STORAGE_TARGET="/opt/marketvalley"
readonly GEUNEUL_HOST_MINIMUM_FREE_KIB=$((40 * 1024 * 1024))
readonly GEUNEUL_HOST_MAXIMUM_INODE_USE_PERCENT=90

geuneul_verify_host_storage() {
  local filesystem_root=""
  local root_major_minor=""
  local target_major_minor=""
  local vfs_options=""

  for command_name in awk cat df findmnt stat; do
    command -v "${command_name}" >/dev/null 2>&1 || fail "${command_name} is required to verify host storage"
  done

  [[ -f "${GEUNEUL_STORAGE_MARKER}" && ! -L "${GEUNEUL_STORAGE_MARKER}" ]] \
    || fail "the reviewed boot-backed storage marker is unavailable"
  [[ "$(stat -c '%u:%g:%a' "${GEUNEUL_STORAGE_MARKER}")" == "0:0:644" ]] \
    || fail "the storage marker must be root-owned mode 0644"
  [[ "$(cat "${GEUNEUL_STORAGE_MARKER}")" == "boot-bind-v1" ]] \
    || fail "Geuneul requires the zero-cost boot-bind-v1 storage layout"

  [[ -d "${GEUNEUL_STORAGE_SOURCE}" && ! -L "${GEUNEUL_STORAGE_SOURCE}" ]] \
    || fail "the boot-backed storage source is missing or unsafe"
  [[ -d "${GEUNEUL_STORAGE_TARGET}" && ! -L "${GEUNEUL_STORAGE_TARGET}" ]] \
    || fail "the shared storage target is missing or unsafe"
  [[ "$(findmnt -n -o TARGET --target "${GEUNEUL_STORAGE_TARGET}")" == "${GEUNEUL_STORAGE_TARGET}" ]] \
    || fail "the shared storage target must be an exact mount point"
  [[ "$(findmnt -n -o FSTYPE --target "${GEUNEUL_STORAGE_TARGET}")" == "ext4" ]] \
    || fail "the shared storage target must use ext4"

  target_major_minor="$(findmnt -n -o MAJ:MIN --target "${GEUNEUL_STORAGE_TARGET}")"
  root_major_minor="$(findmnt -n -o MAJ:MIN --target /)"
  filesystem_root="$(findmnt -n -o FSROOT --target "${GEUNEUL_STORAGE_TARGET}")"
  [[ "${target_major_minor}" == "${root_major_minor}" && "${filesystem_root}" == "${GEUNEUL_STORAGE_SOURCE}" ]] \
    || fail "the shared storage target must bind the exact approved root-filesystem source"
  [[ "$(stat -c '%d:%i' "${GEUNEUL_STORAGE_SOURCE}")" == "$(stat -c '%d:%i' "${GEUNEUL_STORAGE_TARGET}")" ]] \
    || fail "the shared storage source and target do not identify the same directory"
  [[ "$(stat -c '%d' "${GEUNEUL_STORAGE_SOURCE}")" == "$(stat -c '%d' /)" ]] \
    || fail "the shared storage source is not on the root filesystem"

  vfs_options=",$(findmnt -n -o VFS-OPTIONS --target "${GEUNEUL_STORAGE_TARGET}"),"
  [[ "${vfs_options}" == *,nosuid,* && "${vfs_options}" == *,nodev,* ]] \
    || fail "the shared storage target must be mounted nosuid,nodev"

}

geuneul_require_host_capacity() {
  local available_kib=""
  local inode_use_percent=""

  available_kib="$(df -Pk / | awk 'NR == 2 { print $4 }')"
  [[ "${available_kib}" =~ ^[0-9]+$ && "${available_kib}" -ge "${GEUNEUL_HOST_MINIMUM_FREE_KIB}" ]] \
    || fail "the shared host requires at least 40 GiB free on its boot filesystem"
  inode_use_percent="$(df -Pi / | awk 'NR == 2 { value = $5; sub(/%$/, "", value); print value }')"
  [[ "${inode_use_percent}" =~ ^[0-9]+$ \
    && "${inode_use_percent}" -le "${GEUNEUL_HOST_MAXIMUM_INODE_USE_PERCENT}" ]] \
    || fail "the shared host must keep inode use at or below 90%"
}
