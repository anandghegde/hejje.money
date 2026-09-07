# Observability

## Endpoints

| Path | Port (prod) | Auth | Purpose |
|---|---|---|---|
| `/api/v1/server/ping` | 8080 via Caddy | public | Liveness for load balancers and uptime checks |
| `/api/v1/server/health` | 8080 via Caddy | `market:read` | PRD section 41 panel: each check as `{status, detail}` plus `executionEnabled` and `reasons` |
| `/actuator/health`, `/actuator/health/liveness`, `/readiness` | 8081 | public | Spring Boot probes |
| `/actuator/prometheus` | 8081 | public on the management port | Prometheus scrape |

In `prod` the management endpoints listen on port 8081, which Caddy does not proxy, so they are reachable
only inside the compose network or from the VM. In `dev`/`test` they share port 8080.

## Metrics

Custom gauges (all registered by the `system` module):

| Metric | Meaning |
|---|---|
| `hejje_execution_enabled` | 1 when every readiness check allows live execution, else 0 |
| `hejje_egress_ip_verified` | 1 when the egress IP matches `hejje.execution.expected-ips`, else 0 |
| `hejje_clock_drift_seconds` | Local clock minus remote reference clock (NaN until checked or when disabled) |

Standard Spring Boot / Micrometer metrics: `http_server_requests_seconds_*` (per endpoint, status, method),
`jvm_*` (memory, GC, threads), `hikaricp_connections_*` (pool), `process_*`, `system_cpu_*`,
`spring_data_repository_invocations_*`, `executor_*` (scheduled tasks), `logback_events_total` (by level).

## Logs

`dev`: plain text with `[correlationId]`. `prod`: one JSON object per line (`@timestamp`, `level`, `logger_name`,
`thread_name`, `message`, MDC fields including `correlationId`, `stack_trace`). Secrets matching
`api_key|api_secret|access_token|refresh_token|password|authorization` are masked as `***` in both formats.

Status transitions of the egress IP check are logged at WARN and audited as `EGRESS_IP_STATUS_CHANGED`.
