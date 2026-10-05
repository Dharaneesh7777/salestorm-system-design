# SALESTORM – Security & Observability Design

---

# Part A – Security

## 1. Threat Overview

| Threat | Where it appears in a flash sale | Control |
|---|---|---|
| Bots and scalping scripts | Automated Buy Now at sale start | WAF bot rules, rate limits, per-customer purchase limit, optional CAPTCHA / signed purchase token |
| Brute force / credential stuffing | Login endpoint | Login rate limit, lockout with backoff, MFA option, breached-password check |
| DDoS | Edge | CDN / WAF absorption, L7 rate limiting |
| Replay and duplicate submission | Buy Now, Pay | Idempotency-Key, unique constraints |
| Tampered price or amount | Client sends amount | Price computed server-side and stored; payment validates `amount == priced total` |
| Forged payment callback | Gateway webhook | Verify signature, timestamp, then re-query gateway by `payment_id` before trusting |
| Insecure direct object reference | `GET /orders/{id}` | Ownership check: order must belong to the JWT subject |
| Injection (SQL, XSS) | Any input | Parameterised queries, output encoding, schema validation |
| Insider misuse | Admin cancel / refund | Role-based access, audit log, least privilege |
| Secrets leak | Gateway keys, DB passwords | Secrets manager, rotation, never in code or logs |
| Event tampering or snooping | Kafka | TLS, ACLs: only the owner writes its topic |

## 2. Authentication and Authorization

| Aspect | Design |
|---|---|
| Customer authentication | OAuth2 / OIDC; short-lived JWT access token (15 min), refresh token (rotating, 7 days), validated locally at the API Gateway using the identity provider's public keys |
| Token content | `sub` (customerId), `roles`, `exp`, `iss`, `aud`; no sensitive data |
| Authorization model | RBAC: `CUSTOMER`, `SUPPORT`, `ADMIN`, `SERVICE`. Customer may only access own cart, reservation, payment, order. |
| Service-to-service | mTLS inside the cluster plus service identity (SPIFFE or signed service tokens); `SERVICE` role for internal APIs |
| Admin endpoints | Separate path and network policy, MFA required, every call audit-logged |
| Idempotency keys | Scoped per customer (`customerId + key`) so one user cannot read or collide with another user's stored result |

## 3. Transport and Data Protection

| Topic | Design |
|---|---|
| In transit | HTTPS everywhere (TLS 1.2+, HSTS); TLS between services and to Kafka, Redis, MySQL |
| At rest | Disk / database encryption (AES-256); encrypted backups |
| Payment information | **Card data never touches SALESTORM servers.** Hosted payment fields / tokenisation by the gateway; we store only `gateway_ref` and masked reference. This keeps scope at PCI SAQ-A level. |
| PII | Minimised; only CUSTOMER holds personal data; other services use `customerId`; logs mask email and phone |
| Secrets | Secrets manager (Vault / cloud KMS); injected at runtime; automatic rotation of gateway keys and DB passwords |
| Event payloads | Identifiers and facts only; no card or sensitive data |

## 4. Abuse Protection and Rate Limiting

| Level | Limit (example values, tunable) | Response |
|---|---|---|
| CDN / WAF | IP reputation, bot score, geo and ASN rules | Block or challenge |
| Per IP at gateway | 20 req/s sustained on dynamic endpoints | `429` + `Retry-After` |
| Per user on Buy Now | 1 request per second, burst 3; max 1 active reservation per product | `429` or `409` |
| Per user on Login | 5 attempts per minute, then backoff | `429` |
| Payment endpoint | 1 active payment per reservation | Return existing payment (idempotent) |
| Global load shedding | Gateway queue length / CPU threshold | `503` with friendly "high demand" response; waiting room |

Counters live in Redis (sliding window or token bucket) so limits hold across all gateway pods.

## 5. Input Validation (layered)

| Layer | What it checks |
|---|---|
| WAF | Known attack signatures, oversized payloads |
| API Gateway | Auth, required headers (`Idempotency-Key`, `X-Request-Id`), content type, size limits, schema |
| Controller | DTO validation: types, ranges, quantity = 1 for sale items, ID formats |
| Service | Business rules: sale open, per-customer limit, ownership, state |
| Domain | State machine legality (`InvalidStateTransition`) |
| Database | Constraints: unique, check, FK, non-negative |

## 6. Audit Logging

| Item | Detail |
|---|---|
| What is audited | Login success and failure, reservation create / release, payment initiated / succeeded / failed / refunded, order state changes, admin actions, configuration and feature-flag changes, permission changes |
| Fields | who (`customerId` or service), what, resource id, before and after status, `traceId`, source IP, timestamp, outcome |
| Storage | Append-only AUDIT_LOG table plus shipping to central log store; the application DB user cannot UPDATE or DELETE audit rows |
| Retention | At least 1 year (or as regulation requires) |
| Order and reservation history | ORDER_STATUS_HISTORY keeps each transition with `eventId` and reason |

## 7. Secure Operations

| Area | Practice |
|---|---|
| Dependencies | Automated dependency and image scanning in CI; pinned versions |
| Least privilege | Each service has its own DB user restricted to its schema; Kafka ACLs per topic |
| Network | Private subnets for services and data; only the LB and CDN are public; network policies between services |
| Incident response | Runbooks for payment fraud spike, DDoS, credential leak; one-click feature flag to pause the sale |

---

# Part B – Observability

## 1. Three Pillars Overview

| Pillar | Tooling (suggested) | Purpose |
|---|---|---|
| Metrics | Prometheus + Grafana (or cloud equivalent) | Real-time health and capacity |
| Logs | Structured JSON to ELK / Loki | Per-event investigation |
| Traces | OpenTelemetry + Jaeger / Tempo | Follow one purchase across services |

## 2. Key Metrics

### Business metrics
| Metric | Description |
|---|---|
| Reservation attempts / success / failure | Counted by reason: SOLD_OUT, DUPLICATE, RATE_LIMITED, ERROR |
| Reservation expiry and release counts | By reason: EXPIRED, PAYMENT_FAILED, CANCELLED, COMPENSATION |
| Payment success rate / failure rate / timeout rate | Per gateway |
| Order conversion | Paid reservations that reach CONFIRMED order; saga DONE rate |
| Inventory gauges | `available`, `reserved`, `sold` per product (from MySQL) and Redis counter |
| Inventory drift | `redis_counter - mysql_available` (should be 0 or negative and shrinking) |

### Technical metrics (RED + saturation)
| Area | Metrics |
|---|---|
| Gateway / services | Request rate, error rate, latency p50 / p95 / p99 per endpoint |
| Redis | Ops per second, latency, memory, hit ratio, hot-key CPU |
| MySQL | Connections, replication lag, lock waits, deadlocks, slow queries |
| Kafka | Consumer lag per topic and group, retry rate, DLT size |
| Outbox | Unpublished row count and age of oldest row |
| Circuit breaker | State (closed, open, half-open), failure rate, rejected calls |
| Saga | Count per state, age of oldest saga in PAID / COMPENSATING |

### Service-level objectives
| SLO | Target |
|---|---|
| Buy Now latency (p95) | under 200 ms for accepted; under 50 ms for sold-out rejection |
| Availability of Buy Now | 99.95% during sale |
| Order confirmation after payment | under 5 s normal, under 60 s during outage |
| Expired reservation release | within 30 s |
| Oversell | exactly 0 (strict) |

## 3. Structured Logging

Every log line is JSON with common fields: `timestamp`, `level`, `service`, `traceId`, `spanId`, `orderId`, `customerId` (hashed or id only), `eventId`, `event`, `outcome`, `latencyMs`.

| Business event | Logged fields (besides common) |
|---|---|
| `reservation.created` | reservationId, productId, expiresAt, idempotencyKey |
| `reservation.rejected` | reason (SOLD_OUT, DUPLICATE, RATE_LIMITED), gate result |
| `reservation.released` | reservationId, reason, previousStatus |
| `payment.initiated` / `succeeded` / `failed` / `timeout` | paymentId, amount, gatewayRef, attempt, circuitState |
| `payment.reconciled` | paymentId, finalStatus, attempts |
| `order.transition` | orderId, from, to, eventId |
| `message.processed` | topic, type, eventId, outcome (processed, skipped duplicate, retried, dead-lettered) |
| `saga.compensation` | sagaId, step, reason |

Rules: never log card data, tokens or passwords; mask email and phone; log levels controlled by configuration; sampling for high-volume reject logs (for example 1 in 100 for SOLD_OUT) while counters keep exact numbers.

## 4. Distributed Tracing

| Aspect | Design |
|---|---|
| Standard | OpenTelemetry W3C `traceparent` header |
| Origin | API Gateway creates or continues the trace for each request |
| Propagation | HTTP headers between services; `traceId` and `causationId` in the Kafka message envelope so consumers continue the trace |
| Spans | Gateway, idempotency check, Redis gate, reservation transaction, payment gateway call, outbox publish, consumer handling, order transition |
| One purchase view | Search by `orderId` or `traceId` to see: Buy Now, reservation, payment, `PaymentSucceeded`, `ConfirmOrder`, `OrderConfirmed`, `ReservationSold` |
| Sampling | 100% for errors and for payment/order flows; low percentage for browse traffic |

## 5. Alerts

| Alert | Condition | Severity | First action |
|---|---|---|---|
| **Inventory inconsistency** | `available + reserved + sold != total` for any product (should never fire), or Redis counter greater than MySQL available for over 1 min | Critical | Pause gate, run reconciler, investigate |
| Possible oversell | `sold + reserved > total_stock` | Critical (page) | Stop sale flag, audit |
| **Payment failure spike** | Failure rate above 10% for 5 min (baseline 5%) | High | Check gateway status, circuit breaker |
| Payment gateway circuit open | State open for more than 1 min | High | Inform ops; customers see retry message |
| Payment stuck in TIMEOUT | Older than grace period (10 min) | High | Reconciler and manual check |
| **Queue backlog** | Consumer lag above 10 s, or outbox oldest unpublished row above 30 s | High | Scale consumers, check Kafka |
| DLT growth | Any new message in a `.DLT` topic | High | Inspect and replay after fix |
| `PaymentSucceeded` without `OrderConfirmed` | After 60 s | High | Saga watchdog and order reconciler; manual follow-up |
| Reservation release lag | Expired holds older than 30 s still active | Medium | Check expiry scheduler |
| Error rate / latency | 5xx above 1% or p99 above SLO for 5 min | High | Standard on-call |
| Redis / MySQL health | Redis down, replication lag above 5 s | Critical / High | Failover runbook |
| Security | Spike in 401/403/429 per IP, repeated login failures | Medium | Review WAF rules |

## 6. Dashboards

| Dashboard | Panels |
|---|---|
| **Sale war room** | Requests per second, Buy Now accept / reject split, stock gauges (available / reserved / sold), sold-out time, payment success rate, orders confirmed, active reservations, saga states |
| Payment health | Gateway latency, timeouts, circuit state, refunds pending |
| Messaging | Lag per consumer group, retries, DLT size, outbox backlog |
| Infrastructure | CPU, memory, pods, Redis, MySQL, Kafka brokers |

## 7. Reconciliation as Observability

Reconcilers also produce metrics so silent drift is visible: Redis vs MySQL stock, payments TIMEOUT vs resolved, paid payments vs confirmed orders. A non-zero mismatch count after the reconciler runs triggers an alert.
