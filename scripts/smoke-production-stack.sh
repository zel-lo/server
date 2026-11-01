#!/usr/bin/env bash
# Exercise production Compose on an isolated network, volume and loopback port.
set -euo pipefail
source_dir=${1:-/opt/zello}
scratch=$(mktemp -d)
project="zello-check-$$"
umask 077
compose=(docker compose --project-name "$project" --project-directory "$scratch"
  --env-file "$scratch/.env" --env-file "$scratch/.images.env"
  -f "$scratch/compose.yaml" -f "$scratch/check.yaml")
cleanup() {
  code=$?
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$scratch"
  exit "$code"
}
trap cleanup EXIT
cp "$source_dir/compose.yaml" "$source_dir/init-env.sh" \
  "$source_dir/preflight.py" "$source_dir/.images.env.example" "$scratch/"
bash "$scratch/init-env.sh"
sed -i 's/^GITHUB_CLIENT_ID=.*/GITHUB_CLIENT_ID=setup-check/; s/^GITHUB_CLIENT_SECRET=.*/GITHUB_CLIENT_SECRET=setup-check/' "$scratch/.env"
python3 "$scratch/preflight.py"
cat > "$scratch/check.yaml" <<'YAML'
services:
  server:
    environment:
      GITHUB_LOGIN_ENABLED: "false"
  client:
    ports: !override
      - "127.0.0.1::8080"
YAML
"${compose[@]}" up -d --pull never --wait --wait-timeout 240
binding=$("${compose[@]}" port client 8080)
base="http://$binding"
curl --fail --silent --show-error "$base/healthz" >/dev/null
curl --fail --silent --show-error "$base/api/auth/status" >/dev/null
curl --fail --silent --show-error "$base/" > "$scratch/index.html"
python3 -c 'import sys; assert "id=\"root\"" in open(sys.argv[1]).read()' "$scratch/index.html"
"${compose[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -uzello -Dzello -Nse "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1"'
echo 'Production Compose verified: memory limits, health checks, MySQL migrations and frontend/API routing.'
