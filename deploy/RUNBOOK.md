# Hejje runbook

## 1. Provision the VM

- Linux VM (Ubuntu 24.04 LTS, 2 vCPU, 4 GB RAM, 40 GB disk) with a **static public IPv4**. Note the IP.
- DNS `A` record for `HEJJE_DOMAIN` pointing at the static IP. Ports 80 and 443 open; nothing else inbound.
- Install Docker Engine + compose plugin. Create a non-root user in the `docker` group.
- `git clone` the repository to `/opt/hejje` and `cp deploy/.env.example deploy/.env`.
- Fill `deploy/.env`: domain, Postgres password, `HEJJE_ADMIN_PASSWORD`, `HEJJE_JWT_SECRET`
  (`openssl rand -base64 48`), `HEJJE_ENCRYPTION_KEY` (`openssl rand -base64 32`),
  `HEJJE_EXECUTION_EXPECTED_IPS=<static IP>`. Keep `HEJJE_MODE=PAPER` until Phase 1 is verified.

## 2. Register the static IP with Zerodha and create the Kite app

1. Log in to <https://developers.kite.trade>, create an app of type **Connect**.
2. Redirect URL: `https://<HEJJE_DOMAIN>/api/v1/broker/callback`.
3. Note the API key and secret; they go into `deploy/.env` as `HEJJE_KITE_API_KEY` / `HEJJE_KITE_API_SECRET` (Phase 1).
4. Zerodha requires the order-placing server's static IP for API access approval. Submit the VM's static IP
   through the Kite Connect app settings / support ticket as instructed by Zerodha and wait for confirmation.
5. Never place the API secret anywhere but `deploy/.env` on the VM.

## 3. First start

```bash
cd /opt/hejje
docker compose -f deploy/docker-compose.prod.yml up -d --build
docker compose -f deploy/docker-compose.prod.yml logs -f hejje
curl -s https://<HEJJE_DOMAIN>/api/v1/server/ping
```

The first start creates the `admin` user from `HEJJE_ADMIN_PASSWORD`. Log in and check health:

```bash
TOKEN=$(curl -s -X POST https://<HEJJE_DOMAIN>/api/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"admin","password":"..."}' | jq -r .accessToken)
curl -s -H "Authorization: Bearer $TOKEN" https://<HEJJE_DOMAIN>/api/v1/server/health | jq
```

`staticIp` must read `VERIFIED` and `clockSync` `HEALTHY`. If `staticIp` is `MISMATCH`, execution stays
disabled: fix the VM egress or `HEJJE_EXECUTION_EXPECTED_IPS`.

Create API keys for the TUI and agents with `POST /api/v1/auth/clients` (see `docs/api.md`).

## 3a. Existing reverse proxy on the host (hejje.malgudi.app)

When nginx (or anything else) already owns ports 80/443 on the VM, do not start Caddy. Keep an untracked
`deploy/docker-compose.host.yml` on the VM:

```yaml
# Local to this VM. nginx terminates TLS; do not start Caddy on 80/443.
services:
  caddy:
    profiles: ["caddy"]
  hejje:
    ports:
      - "127.0.0.1:8090:8080"
```

and pass both files: `docker compose -f deploy/docker-compose.prod.yml -f deploy/docker-compose.host.yml up -d --build`.
The host proxy terminates TLS for `HEJJE_DOMAIN`, serves `web/dist`, and proxies `/api` and `/ws` (with
WebSocket upgrade headers) to `127.0.0.1:8090`. Port 8081 must stay unpublished.

## 4. Required environment variables

See `deploy/.env.example` and `docs/config.md`. In `prod` the server refuses to start without
`HEJJE_JWT_SECRET`, and without `HEJJE_ADMIN_PASSWORD` when no user exists yet.

## 5. Upgrade

```bash
cd /opt/hejje && git pull
deploy/backup.sh
docker compose -f deploy/docker-compose.prod.yml up -d --build
docker compose -f deploy/docker-compose.prod.yml logs --tail=100 hejje
```

Flyway applies new migrations on start. Upgrade outside market hours (after 15:30 IST).

## 6. Backups and restore

- `deploy/backup.sh` writes `data/backups/hejje-<stamp>.sql.gz`, keeps 14. Schedule it nightly with cron.
- Copy `data/` off the VM regularly (backups plus parquet history from Phase 1).

Restore:

```bash
docker compose -f deploy/docker-compose.prod.yml stop hejje
gunzip -c data/backups/hejje-<stamp>.sql.gz | docker exec -i hejje-postgres psql -U hejje -d hejje
docker compose -f deploy/docker-compose.prod.yml start hejje
```

For a fresh database, drop and recreate `hejje` first (`dropdb`/`createdb` inside the container).

## 7. Bare metal (systemd)

Build `server/build/libs/hejje-server-*.jar`, copy to `/opt/hejje/hejje.jar`, put the env vars in
`/etc/hejje/hejje.env`, install `deploy/systemd/hejje.service`. Run Postgres and Caddy as you prefer.

## 8. Observability

Prometheus metrics are served on the management port 8081 at `/actuator/prometheus`, reachable only inside
the compose network. See `docs/observability.md`.
