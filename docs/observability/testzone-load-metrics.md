# TestZone Load Metrics

TestZone preserves k6's metric meanings instead of combining unrelated signals.
The browser and Grafana use one-second buckets for live charts and retain the
raw k6 JSON output for later inspection.

## Load-test metrics

| TestZone label | k6 source | Meaning |
| --- | --- | --- |
| TPS / RPS | `http_reqs` | Completed HTTP requests per second |
| MTTFB | average `http_req_waiting` | Mean time from request send completion until the first response byte |
| MTT | average `http_req_duration` | Mean HTTP send, wait, and receive time; connection setup is excluded |
| HTTP success | `http_reqs - http_req_failed` | Requests k6 classified as expected responses |
| HTTP errors | sum of `http_req_failed` point values | Requests k6 classified as failed responses |
| avg / p90 / p95 | `http_req_duration` samples | Response-time distribution in each one-second bucket |

`check()` results remain a separate semantic validation signal. A response can
be HTTP-successful while a body check fails, so TestZone does not rewrite check
failures as HTTP errors.

k6 does not automatically know the application's business transaction
boundary. When one business transaction performs multiple HTTP requests,
`http_reqs` is request throughput, not business TPS. The script must add a
custom k6 `Counter` for completed transactions and another counter for failed
transactions when that distinction is required.

## Test component metrics

TestZone samples every deployed PostgreSQL and Redis component every five
seconds and writes the values to InfluxDB.

- Docker: CPU, used/limit memory, memory percentage, process count, network I/O,
  and block I/O
- PostgreSQL: total/active/max connections, database size, and buffer cache hit
  ratio
- Redis: used/configured memory, connected clients, operations per second, and
  keyspace hit ratio

Docker CPU and memory remain available if a native PostgreSQL or Redis probe
temporarily fails. Native probe failure never changes the component lifecycle
state.

## Component configuration

The PostgreSQL component exposes the operational settings that materially
affect load tests as first-class fields: max connections, shared buffers, work
memory, maintenance work memory, effective cache size, and statement timeout.
TestZone validates these values against safe bounds and the selected container
memory before saving them. They are applied as PostgreSQL server parameters
when the component is restarted.

The Components tab also accepts up to 50 extra key-value environment settings
per component. Keys are normalized to uppercase and values are stored in the
TestZone data directory with file mode `0600`. PostgreSQL database, username,
password, and first-class server settings remain TestZone-managed to prevent
credentials and runtime parameters from diverging. Resetting additionally
deletes the component's persistent volume.
