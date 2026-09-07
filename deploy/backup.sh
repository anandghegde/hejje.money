#!/usr/bin/env bash
# Nightly Postgres dump, keeps the newest 14. Cron: 30 1 * * * /opt/hejje/deploy/backup.sh
set -euo pipefail
BACKUP_DIR="${HEJJE_BACKUP_DIR:-$(dirname "$0")/../data/backups}"
CONTAINER="${HEJJE_PG_CONTAINER:-hejje-postgres}"
KEEP="${HEJJE_BACKUP_KEEP:-14}"
mkdir -p "$BACKUP_DIR"
stamp="$(date +%Y%m%d-%H%M%S)"
file="$BACKUP_DIR/hejje-$stamp.sql.gz"
docker exec "$CONTAINER" pg_dump -U hejje -d hejje --no-owner | gzip > "$file"
echo "wrote $file"
ls -1t "$BACKUP_DIR"/hejje-*.sql.gz | tail -n +"$((KEEP + 1))" | xargs -r rm --
