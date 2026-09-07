# Configuration reference

All settings can be provided as environment variables (`HEJJE_*`) or in `application.yml`.
Secrets are environment variables only.

| Key | Env var | Default | Description |
|---|---|---|---|
| `hejje.mode` | `HEJJE_MODE` | `PAPER` | Global execution mode: `PAPER`, `CONFIRM`, `AUTO`. `CONFIRM`/`AUTO` refuse to start unless the `prod` profile is active. |
| `hejje.timezone` | — | `Asia/Kolkata` | Business time zone for session logic. |
| `hejje.data-dir` | `HEJJE_DATA_DIR` | `./data` | Directory for server-owned files (parquet, backups). |
| `spring.datasource.url` | `HEJJE_DB_URL` | `jdbc:postgresql://localhost:5432/hejje` | PostgreSQL JDBC URL. |
| `spring.datasource.username` | `HEJJE_DB_USER` | `hejje` | Database user. |
| `spring.datasource.password` | `HEJJE_DB_PASSWORD` | `hejje` | Database password. |
| `spring.profiles.active` | `SPRING_PROFILES_ACTIVE` | `dev` | `dev` (plain logs), `test`, `prod` (JSON logs). |
| `spring.threads.virtual.enabled` | — | `true` | Virtual threads for request handling. |
| `hejje.auth.admin-password` | `HEJJE_ADMIN_PASSWORD` | — | Bootstrap password for `admin`, read only when no user exists. Required in `prod` on first start. |
| `hejje.auth.jwt-secret` | `HEJJE_JWT_SECRET` | — | HS256 secret, at least 32 bytes. Required in `prod`; otherwise a random per-start secret is used. |
| `hejje.auth.access-token-ttl` | — | `15m` | JWT lifetime. |
| `hejje.auth.refresh-token-ttl` | — | `12h` | Refresh cookie lifetime. |
| `hejje.auth.rate-limit.default-rate` / `default-burst` | — | `20` / `40` | Per-principal requests per second and bucket size. |
| `hejje.auth.rate-limit.transactional-rate` / `transactional-burst` | — | `5` / `5` | Limits for non-GET calls under `transactional-paths`. |
| `hejje.auth.rate-limit.transactional-paths` | — | `/api/v1/orders, /api/v1/positions, /api/v1/risk, /api/v1/auth/clients` | Path prefixes that count as transactional. |
