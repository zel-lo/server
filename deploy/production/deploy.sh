#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$root"
umask 077
exec 9> "$root/.deploy.lock"
flock -w 600 9

service=${1:-all}
image=${2:-}
case "$service" in
  client|server)
    [[ "$image" =~ ^ghcr\.io/zel-lo/$service:sha-[0-9a-f]{40}$ ]] || {
      echo 'Expected a verified ghcr.io/zel-lo/SERVICE:sha-COMMIT image.' >&2; exit 1;
    } ;;
  all) [[ $# == 0 ]] || { echo 'Usage: deploy.sh [client|server IMAGE]' >&2; exit 1; } ;;
  *) echo 'Usage: deploy.sh [client|server IMAGE]' >&2; exit 1 ;;
esac

candidate=$(mktemp "$root/.images.candidate.XXXXXX")
trap 'rm -f "$candidate"' EXIT
cp .images.env "$candidate"
if [[ "$service" != all ]]; then
  variable=$(printf '%s_IMAGE' "$service" | tr '[:lower:]' '[:upper:]')
  sed -i "s|^$variable=.*|$variable=$image|" "$candidate"
fi
python3 "$root/preflight.py" "$candidate"
compose=(docker compose --project-name zello --project-directory "$root"
  --env-file "$root/.env" --env-file "$candidate" -f "$root/compose.yaml")

# Pull only this repository's image with the temporary CI registry credentials.
# The other service and MySQL are preloaded once during server setup.
if [[ "$service" != all ]]; then docker pull "$image"; fi
existing=$(docker ps -q --filter label=com.docker.compose.project=zello)
activate_candidate() {
  "${compose[@]}" up -d --pull never --wait --wait-timeout 240 || return $?
  # Nginx resolves the backend on startup; refresh it after backend replacement.
  if [[ "$service" == server ]]; then
    "${compose[@]}" up -d --pull never --no-deps --force-recreate --wait --wait-timeout 60 client || return $?
  fi
  curl --fail --silent --show-error --retry 5 --retry-delay 2 \
      --retry-all-errors --max-time 5 http://127.0.0.1:8083/healthz >/dev/null \
      && curl --fail --silent --show-error --max-time 10 \
      http://127.0.0.1:8083/api/auth/status >/dev/null
}
if activate_candidate; then
  cp .images.env .images.previous.env
  mv "$candidate" .images.env
  echo 'Deployment healthy: frontend, backend and MySQL are running.'
  exit 0
fi

echo 'Deployment failed; image selection was not committed.' >&2
if [[ -n "$existing" && "$service" != all ]]; then
  echo 'Restoring the previous application images; database volume is preserved.' >&2
  docker compose --project-name zello --project-directory "$root" \
    --env-file "$root/.env" --env-file "$root/.images.env" -f "$root/compose.yaml" \
    up -d --pull never --wait --wait-timeout 240
  if [[ "$service" == server ]]; then
    docker compose --project-name zello --project-directory "$root" \
      --env-file "$root/.env" --env-file "$root/.images.env" -f "$root/compose.yaml" \
      up -d --pull never --no-deps --force-recreate --wait --wait-timeout 60 client
  fi
fi
exit 1
