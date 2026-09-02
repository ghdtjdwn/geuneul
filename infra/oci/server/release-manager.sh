#!/usr/bin/env bash
set -Eeuo pipefail

readonly managed_release_script="/usr/local/lib/geuneul/remote-release.sh"

case "${1:-}" in
  current|stage|start-data|activate|deploy)
    [[ -f "$managed_release_script" && ! -L "$managed_release_script" ]] || {
      printf 'geuneul release manager: trusted release script is unavailable\n' >&2
      exit 1
    }
    exec bash "$managed_release_script" "$1"
    ;;
  *)
    printf 'usage: release-manager.sh [current|stage|start-data|activate|deploy]\n' >&2
    exit 1
    ;;
esac
