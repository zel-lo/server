#!/usr/bin/env bash
set -euo pipefail

image=${1:?Usage: smoke-container.sh IMAGE}
prefix="zello-server-smoke-$$"
scratch=$(mktemp -d)
cleanup() {
  result=$?
  if (( result != 0 )); then
    docker logs "$prefix-app" 2>&1 || true
    docker logs "$prefix-db" 2>&1 || true
  fi
  docker rm -fv "$prefix-app" "$prefix-db" >/dev/null 2>&1 || true
  docker network rm "$prefix" >/dev/null 2>&1 || true
  rm -rf "$scratch"
}
trap cleanup EXIT

runtime_user=$(docker image inspect --format '{{.Config.User}}' "$image")
case "$runtime_user" in ''|root|0|0:*) echo 'Container must run as a non-root user' >&2; exit 1 ;; esac
docker network create "$prefix" >/dev/null
docker run -d --name "$prefix-db" --network "$prefix" --network-alias mysql \
  -e MYSQL_ROOT_PASSWORD=smoke-root-only \
  -e MYSQL_DATABASE=zello -e MYSQL_USER=zello -e MYSQL_PASSWORD=smoke-only \
  mysql:8.4 >/dev/null
ready=false
for (( attempt=0; attempt<90; attempt++ )); do
  if docker exec -e MYSQL_PWD=smoke-only "$prefix-db" mysql -h127.0.0.1 -uzello -Nse 'SELECT 1' >/dev/null 2>&1; then
    ready=true; break
  fi
  sleep 1
done
test "$ready" = true
docker run -d --name "$prefix-app" --network "$prefix" -p 127.0.0.1::8080 \
  --read-only --tmpfs /tmp:rw,noexec,nosuid,size=128m \
  -e 'DB_URL=jdbc:mysql://mysql:3306/zello?serverTimezone=UTC&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false' \
  -e DB_USERNAME=zello -e DB_PASSWORD=smoke-only \
  -e GITHUB_LOGIN_ENABLED=false \
  -e SESSION_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= \
  "$image" >/dev/null
binding=$(docker port "$prefix-app" 8080/tcp)
base="http://127.0.0.1:${binding##*:}"
curl --fail --silent --show-error --retry 60 --retry-delay 2 --retry-all-errors \
  --max-time 3 "$base/actuator/health" > "$scratch/health.json"
python3 -c 'import json,sys; assert json.load(open(sys.argv[1]))["status"] == "UP"' "$scratch/health.json"
curl --fail --silent --show-error "$base/api/auth/status" > /dev/null
applied=$(docker exec -e MYSQL_PWD=smoke-only "$prefix-db" mysql -uzello -Dzello -Nse 'SELECT COUNT(*) FROM flyway_schema_history WHERE success=1')
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
expected=$(python3 -c 'from pathlib import Path; import sys; print(len(list(Path(sys.argv[1]).glob("V*__*.sql"))))' "$script_dir/../zello/src/main/resources/db/migration")
test "$applied" = "$expected"
echo "Server container: MySQL startup, $applied migrations, JPA validation and health check passed."
