#!/usr/bin/env bash
set -euo pipefail
[[ $EUID == 0 ]] || { echo 'Run with sudo.' >&2; exit 1; }
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
target=/etc/nginx/sites-available/zz-zello
link=/etc/nginx/sites-enabled/zz-zello
backup=$(mktemp -d /etc/nginx/zello-backup.XXXXXX)
had_config=false
if [[ -f "$target" ]]; then cp -a "$target" "$backup/zz-zello"; had_config=true; fi
install -d -m 755 /var/www/zello/.well-known/acme-challenge
install -m 644 "$root/unavailable.html" /var/www/zello/unavailable.html

activate() {
  install -m 644 "$1" "$target"
  ln -sfn "$target" "$link"
  if ! /usr/sbin/nginx -t; then
    if [[ "$had_config" == true ]]; then cp -a "$backup/zz-zello" "$target";
    else rm -f "$link" "$target"; fi
    echo "Nginx validation failed; prior configuration restored. Backup: $backup" >&2
    return 1
  fi
  systemctl reload nginx
}

if [[ ! -f /etc/letsencrypt/live/zello.asia/fullchain.pem ]]; then
  activate "$root/nginx-http.conf"
  certbot certonly --webroot -w /var/www/zello --cert-name zello.asia \
    -d zello.asia -d www.zello.asia --non-interactive --agree-tos
fi
activate "$root/nginx-https.conf"
install -d -m 755 /etc/letsencrypt/renewal-hooks/deploy
cat > /etc/letsencrypt/renewal-hooks/deploy/zello-reload-nginx <<'HOOK'
#!/bin/sh
set -eu
/usr/sbin/nginx -t
systemctl reload nginx
HOOK
chmod 755 /etc/letsencrypt/renewal-hooks/deploy/zello-reload-nginx
systemctl enable --now certbot.timer
echo "HTTPS configured for zello.asia and www.zello.asia. Nginx backup: $backup"
