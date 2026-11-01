#!/usr/bin/env bash
# Forced SSH command for the deployment-only key. No shell/forwarding access.
set -euo pipefail
read -r verb service sha actor extra <<< "${SSH_ORIGINAL_COMMAND:-}"
[[ "$verb" == zello-deploy && -z "${extra:-}" ]] || {
  echo 'Use zello-deploy SERVICE SHA ACTOR.' >&2; exit 1;
}
[[ "$service" == client || "$service" == server ]]
[[ "$sha" =~ ^[0-9a-f]{40}$ ]]
[[ "$actor" =~ ^[a-zA-Z0-9_-]+(\[bot\])?$ ]]
umask 077
export DOCKER_CONFIG=$(mktemp -d)
trap 'rm -rf -- "$DOCKER_CONFIG"' EXIT
docker login ghcr.io --username "$actor" --password-stdin >/dev/null
/opt/zello/deploy.sh "$service" "ghcr.io/zel-lo/$service:sha-$sha"
