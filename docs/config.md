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
