#!/usr/bin/env bash
# Deploy main: push it to origin (GitHub), have the VM pull origin/main and run deploy/upgrade.sh in a detached tmux
# session, then follow its log. Ctrl-C stops following, not the upgrade (reattach: ssh <host> tmux attach -t hejje-upgrade).
#   deploy/deploy.sh [--now]    --now restarts immediately instead of waiting for the market to close
set -euo pipefail
cd "$(dirname "$0")/.."

HOST="${HEJJE_DEPLOY_HOST:-root@hejje.malgudi.app}"
DIR="${HEJJE_DEPLOY_DIR:-/opt/hejje}"
LOG="$DIR/data/deploy/upgrade-$(date +%Y%m%d-%H%M%S).log"

[[ "$(git branch --show-current)" == main ]] || { echo "not on main: the VM deploys origin/main"; exit 1; }
git diff --quiet HEAD || { echo "uncommitted changes: commit first (only committed main is deployed)"; exit 1; }

git push origin main
ssh "$HOST" "cd $DIR && git pull --ff-only origin main && mkdir -p data/deploy && tmux new-session -d -s hejje-upgrade \
  'cd $DIR && deploy/upgrade.sh ${1:-} > $LOG 2>&1; echo \$? > $LOG.exit'"
echo "upgrade started on $HOST, log $LOG"

ssh -t "$HOST" "tail -n +1 -F $LOG 2>/dev/null & t=\$!; until [ -f $LOG.exit ]; do sleep 2; done; sleep 1; kill \$t; exit \$(cat $LOG.exit)"
