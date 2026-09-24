#!/usr/bin/env bash
# Upgrade to the code in this checkout: backup, build, restart outside market hours, health check, web bundle.
# Started by deploy/deploy.sh after it fast-forwards the checkout; can also be run by hand in /opt/hejje.
#   deploy/upgrade.sh [--now]    --now restarts immediately instead of waiting for the market to close
set -euo pipefail
cd "$(dirname "$0")/.."

NOW=false
[[ "${1:-}" == "--now" ]] && NOW=true
PING="${HEJJE_PING_URL:-http://127.0.0.1:8090/api/v1/server/ping}"
WEB_ROOT="${HEJJE_WEB_ROOT:-/var/www/hejje}"
COMPOSE=(docker compose -f deploy/docker-compose.prod.yml)
[[ -f deploy/docker-compose.host.yml ]] && COMPOSE+=(-f deploy/docker-compose.host.yml)

exec 9>/tmp/hejje-upgrade.lock
flock -n 9 || { echo "another upgrade is running"; exit 1; }

log() { echo "[$(TZ=Asia/Kolkata date '+%F %T IST')] $*"; }

log "upgrading to $(git rev-parse --short HEAD)"

log "backup"
deploy/backup.sh

log "build image"
"${COMPOSE[@]}" build hejje

log "build web"
(cd web && npm ci --no-audit --no-fund && npm run build)

# Restart only outside 09:00-15:30 IST on weekdays (exchange holidays are not known here: use --now).
if ! $NOW; then
  while :; do
    dow=$(TZ=Asia/Kolkata date +%u); hm=$(TZ=Asia/Kolkata date +%H%M)
    if (( dow <= 5 && 10#$hm >= 900 && 10#$hm <= 1530 )); then
      log "market hours; waiting for 15:31 IST"
      sleep 60
    else
      break
    fi
  done
fi

log "restart"
"${COMPOSE[@]}" up -d --no-build hejje

log "health check"
for _ in $(seq 1 60); do
  if curl -fsS "$PING" > /dev/null 2>&1; then
    rsync -a --delete web/dist/ "$WEB_ROOT/"
    log "DEPLOY OK $(git rev-parse --short HEAD) (orders are read-only for ~40 s while the executor lease moves over)"
    exit 0
  fi
  sleep 5
done
"${COMPOSE[@]}" logs --tail=100 hejje
log "DEPLOY FAILED: no ping after 5 minutes; web bundle not updated; backup is the newest file in data/backups"
exit 1
