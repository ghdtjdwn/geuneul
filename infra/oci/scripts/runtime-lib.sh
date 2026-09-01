#!/usr/bin/env bash

resolve_compose() {
  if docker compose version >/dev/null 2>&1; then
    COMPOSE=(docker compose)
  elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE=(docker-compose)
  else
    printf 'Docker Compose v2 is unavailable.\n' >&2
    return 1
  fi
}

require_regular_0600_file() {
  local path="$1"
  local label="$2"
  local mode=""

  [[ "$path" == /* ]] || { printf '%s path must be absolute.\n' "$label" >&2; return 1; }
  [[ -f "$path" && ! -L "$path" ]] || { printf '%s must be a regular non-symlink file.\n' "$label" >&2; return 1; }
  mode="$(stat -f '%Lp' "$path" 2>/dev/null || stat -c '%a' "$path")"
  [[ "$mode" == "600" ]] || { printf '%s mode must be 0600.\n' "$label" >&2; return 1; }
}

read_env_value() {
  local env_file="$1"
  local key="$2"
  local count=""
  local value=""

  count="$(grep -c "^${key}=" "$env_file" || true)"
  [[ "$count" == "1" ]] || { printf '%s must appear exactly once.\n' "$key" >&2; return 1; }
  value="$(sed -n "s/^${key}=//p" "$env_file" | tr -d '\r')"
  [[ -n "$value" ]] || { printf '%s must not be empty.\n' "$key" >&2; return 1; }
  printf '%s' "$value"
}

