#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
umask 077
if [[ ! -e .env ]]; then
  {
    printf 'MYSQL_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'MYSQL_ROOT_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'SESSION_ENCRYPTION_KEY=%s\n' "$(openssl rand -base64 32)"
    printf '\n# Production OAuth App; callback: https://zello.asia/login/oauth2/code/github\n'
    printf 'GITHUB_CLIENT_ID=\nGITHUB_CLIENT_SECRET=\n'
  } > .env
  echo 'Created .env with database passwords and a session encryption key. Fill the two GitHub OAuth values.'
else
  echo 'Existing .env preserved.'
fi
chmod 600 .env
if [[ ! -e .images.env ]]; then
  cp .images.env.example .images.env
fi
chmod 600 .images.env
