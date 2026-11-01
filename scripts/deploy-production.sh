#!/usr/bin/env bash
set -euo pipefail
service=${1:?Usage: deploy-production.sh client|server}
case "$service" in client|server) ;; *) exit 1 ;; esac
: "${DEPLOY_HOST:?Set DEPLOY_HOST}"
: "${DEPLOY_USER:?Set DEPLOY_USER}"
: "${DEPLOY_SSH_KEY:?Set DEPLOY_SSH_KEY}"
: "${DEPLOY_KNOWN_HOSTS:?Set DEPLOY_KNOWN_HOSTS}"
: "${GHCR_TOKEN:?Set GHCR_TOKEN}"
: "${GHCR_USER:?Set GHCR_USER}"
: "${GITHUB_SHA:?Set GITHUB_SHA}"
[[ "$GITHUB_SHA" =~ ^[0-9a-f]{40}$ ]]
[[ "$DEPLOY_HOST" =~ ^[a-zA-Z0-9.-]+$ && "$DEPLOY_USER" =~ ^[a-zA-Z0-9_-]+$ ]]
[[ "$GHCR_USER" =~ ^[a-zA-Z0-9_-]+(\[bot\])?$ ]]
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
chmod 700 "$scratch"
printf '%s\n' "$DEPLOY_SSH_KEY" > "$scratch/key"
printf '%s\n' "$DEPLOY_KNOWN_HOSTS" > "$scratch/known_hosts"
chmod 600 "$scratch/key" "$scratch/known_hosts"

# The token travels over encrypted stdin and is removed from the VM on exit.
printf '%s' "$GHCR_TOKEN" | ssh -i "$scratch/key" \
  -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes \
  -o ConnectTimeout=15 -o UserKnownHostsFile="$scratch/known_hosts" \
  "$DEPLOY_USER@$DEPLOY_HOST" "zello-deploy $service $GITHUB_SHA $GHCR_USER"
