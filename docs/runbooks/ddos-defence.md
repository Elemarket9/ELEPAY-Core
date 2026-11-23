# DDoS and abuse defence

No system is unbreakable. The goal is that every layer an attacker reaches costs them more than it costs
ELEPAY, and that an attack degrades service rather than taking it down or corrupting money.

## Layers

| # | Layer | Stops | Where |
|---|-------|-------|-------|
| 1 | CDN / scrubbing provider | Volumetric L3/L4 floods (SYN, UDP, amplification), huge L7 floods, bot traffic | Provider (contract required) |
| 2 | Edge proxy (nginx) | Slowloris, oversized requests, per-IP / per-API-key rate, connection caps, HTTP/2 stream floods, direct-to-origin traffic | `deploy/edge/nginx.conf` |
| 3 | Tomcat connector | Idle/trickling connections, header floods, socket exhaustion | `server.tomcat.*` in `application.properties` |
| 4 | `EdgeProtectionFilter` | Banned clients, oversized/endless bodies, long URIs, per-client rate across all routes, overload (fast 503 load shedding) | `com.elepay.identity` |
| 5 | `AbusePenaltyBox` | Credential/signature brute force and route scanning: 401/403/404 strikes escalate to bans of 1 min → 5 min → 30 min → 2 h → 24 h | `com.elepay.identity` |
| 6 | `RateLimitFilter` | Card testing, account enumeration, public checkout and USSD floods (tight per-route limits) | `com.elepay.identity` |
| 7 | Request signing, idempotency, JSON parser limits | Replay, duplicate money movement, parser CPU/memory attacks (nesting 64, numbers 100 digits) | `RequestSigningFilter`, `Idempotency`, `JsonParsingLimits` |
| 8 | Ledger invariants | Even a successful flood cannot create or duplicate money: balanced postings, ordered locks, idempotent references | `com.elepay.ledger` |

Layers 1–2 are infrastructure ELEPAY must deploy; the application cannot absorb a network flood by itself.

## What an attacker experiences

- Brute forcing API keys: about 15 bad keys in 10 minutes → banned before authentication runs, with no
  database lookup. Each repeat ban is longer; the ban ladder is remembered for 24 h after a ban ends.
- Scanning routes: every 404/405 is a strike.
- Big or endless bodies: cut off at 1 MiB at the edge and again in the JVM, plus 3 strikes.
- Flooding: per-IP limits at nginx and in the app; past the in-flight cap, requests get 503 in under 50 ms
  while admitted requests keep normal latency.
- Banned and oversized responses send `Connection: close`, forcing a new TCP + TLS handshake per attempt.

## Configuration

| Property | Default | Notes |
|----------|---------|-------|
| `elepay.edge.max-body-bytes` | 1048576 | Keep equal to nginx `client_max_body_size` |
| `elepay.edge.per-client.burst` / `.per-minute` | 300 / 1200 | Per address, all routes. CGNAT: keep generous |
| `elepay.edge.max-in-flight` | 400 | Per instance. Rule of thumb: 4–8 × DB pool size |
| `elepay.abuse.strike-threshold` | 60 | Points per 10 min. 401 = 4, 403 = 2, 404/405 = 1, oversize/long URI = 3, rate-limited = 1 |
| `elepay.abuse.exempt-addresses` | empty | Partner callback ranges and monitors. IP literals/CIDRs only |
| `server.tomcat.connection-timeout` | 10s | Slowloris |
| `server.tomcat.max-connections` | 8192 | Per instance |

Shared state (bans, limits) lives in Redis so every instance agrees. Redis 5+ is required (scripts read the
Redis server clock). If Redis fails, each component trips a 5-second breaker and uses bounded in-memory
state: protection weakens per instance but never adds Redis timeouts to requests. Route limits in
`RateLimitFilter` fail closed in production (`elepay.rate-limits.shared-required=true`).

## Client IP must be trustworthy

All per-client controls key on `request.getRemoteAddr()`. In production `server.forward-headers-strategy=native`
lets Tomcat take the client from `X-Forwarded-For`. That is only safe if:

1. the origin accepts connections only from the edge proxy (firewall / security group), and
2. `server.tomcat.remoteip.internal-proxies` matches the edge proxy addresses, and
3. nginx overwrites `X-Forwarded-For` with the real client (`elepay_proxy.conf` does).

Otherwise attackers can spoof their address and dodge every per-client limit.

## During an attack

1. Watch `elepay_edge_rejected_total{reason}` and `elepay_edge_in_flight` in Prometheus/Grafana.
   - `shed` rising: capacity problem. Scale out instances; check DB latency before raising `max-in-flight`.
   - `client_rate` / `banned` from few addresses: targeted attack; block those at the CDN.
   - `client_rate` from very many addresses: distributed attack. Enable the CDN's challenge mode for
     public routes and tighten nginx `per_ip` rates; the in-memory limiter fails closed when saturated.
2. Never disable request signing, idempotency or ledger checks to "get traffic through".
3. Payment correctness outranks availability: shedding returns 503 before work starts, so no partial
   money movement happens.
4. Afterwards, review bans and false positives (especially carrier CGNAT ranges) and adjust thresholds.

## Still required (not code)

- A CDN / DDoS scrubbing contract with an African point of presence.
- Origin firewalling so only the edge can reach application instances.
- Load testing in a staging environment to set `max-in-flight` and rate values from measurements.
- Penetration test by an independent firm before go-live (supports BoG licensing and ISO 27001 alignment).
