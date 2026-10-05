# SALESTORM – Scalability & Reliability Design

**Core idea:** traffic grows 50x, but the **number of business transactions does not**. Only 100 units exist, so at most about 100 reservations, 100 payments and 100 orders happen no matter how many people click. The design therefore absorbs traffic at the edge and in Redis, and keeps the expensive, stateful services (MySQL, payment gateway, order) small and protected.

---

## 1. Load Model

| Scenario | Request rate | Notes |
|---|---|---|
| Normal day | about 10,000 req/s | Mostly browse, search, cart; cacheable |
| Flash-sale peak (design target) | up to 500,000 req/s | 50x normal; dominated by product page reads and Buy Now attempts |
| Practical test | 10,000 concurrent Buy Now, 100 units | About 9,800 unique after 2% duplicates |

Assumed split at peak: about 85% reads (sale page, stock flag, static assets), about 14% Buy Now attempts, about 1% everything else. Reads are served by CDN and cache; only Buy Now needs dynamic work.

### Where load is absorbed (funnel)

| Layer | Requests reaching it (peak) | How the reduction happens |
|---|---|---|
| CDN / WAF | 500,000 /s arrive | Static assets and the sale page cached at edge; bots and abusive IPs dropped |
| Load balancer + API Gateway | Dynamic traffic only | Per-IP and per-user rate limits; JWT check; idempotency key required |
| Redis (idempotency + stock gate) | Buy Now attempts | Duplicates answered from stored result; stock counter at 0 rejects instantly |
| Inventory service + MySQL | **about 100 reservations** | Only requests that pass the atomic gate |
| Payment service + gateway | **about 100 payments** (95 succeed) | Bounded by stock, not traffic |
| Order service | **about 95 orders** | Bounded by successful payments |

---

## 2. Scaling Strategy per Component

| Component | Scaling approach | State management |
|---|---|---|
| CDN / WAF | Managed, global edge; pre-warm sale page; cache static JSON of product and sale info (TTL 5-30 s) | Stateless |
| Load balancer | Multi-AZ, L7; health checks and connection draining | Stateless |
| API Gateway | Horizontal pods behind LB, autoscale on CPU and request rate; **pre-scale before sale start** (predictable event) | Stateless (JWT validation is local) |
| Product / Sale / Cart services | Horizontal; read replicas; Redis cache-aside for product and sale data | Stateless; cart in Redis with DB persistence |
| Inventory-Reservation | Horizontal service pods; contention is in Redis (atomic) then one DB row | Stateless; truth in MySQL, gate in Redis |
| Checkout (saga) | Horizontal; saga state in DB, so any pod can resume a saga | State in SAGA_STATE table |
| Payment | Horizontal; bulkhead thread pool toward the gateway; bounded by about 100 charges | State in DB |
| Order | Horizontal Kafka consumers (partition key `orderId`) | State in DB |
| Notification / Shipment | Consumers scaled by lag; never on the purchase path | Idempotent by `eventId` |
| Redis | Clustered with replicas; the sale stock counter lives on one shard (single key is atomic); a hot key is acceptable because the operation is O(1) | Rebuildable from MySQL |
| MySQL | Primary plus replicas per service; writes on the hot product row are about 100 total | Strong consistency on primary |
| Kafka | 3+ brokers, replication factor 3, `acks=all`, partitions by `orderId` (for example 12-24) | Durable log, 7-day retention |

### Redis gate throughput
A single Redis shard handles on the order of 100k simple atomic operations per second. At 500k req/s the gate is only reached by the dynamic Buy Now portion, and **most of those are rejected earlier** by rate limits and the idempotency cache. If a single key ever became too hot, add a **local pre-filter** in each gateway pod: once a pod has seen `SOLD_OUT` from Redis it caches that answer for 1-2 seconds, so repeated clicks never leave the pod.

---

## 3. Bottleneck Analysis

| # | Likely bottleneck | Why | Mitigation | Residual risk |
|---|---|---|---|---|
| 1 | Edge / gateway under 500k req/s | Raw connection and TLS load | CDN caching, WAF, autoscale and pre-scale, connection limits, rate limiting | Cost of over-provisioning; cold start if not pre-scaled |
| 2 | Redis stock gate (hot key) | All Buy Now requests hit one counter | O(1) Lua script, idempotency check first, pod-local sold-out cache, rate limit | Redis outage means gate closed (fail closed) |
| 3 | MySQL inventory row | Single hot row | Redis lets only about 100 requests through; conditional update; no long transactions | Redis drift sends extra requests; DB rejects them safely |
| 4 | Payment gateway | External, slow, rate-limited | Only about 100 calls; timeout 3 s, retry same `payment_id`, circuit breaker, bulkhead, reconciler | Outage longer than reservation TTL means holds expire and stock returns |
| 5 | Kafka consumer lag | Event bursts after sale start | Partitions by `orderId`, scale consumers on lag, outbox buffers | Delayed confirmation (target under 5 s, under 60 s in outage) |
| 6 | Sale page reads | 85% of traffic | CDN + Redis cache + stock flag via `StockLevelChanged` event; stale value acceptable | UI may show stale stock for a few seconds |

**Most likely real bottleneck:** the edge and the Redis gate, not the database. This is why the design puts all 10,000 requests through cheap operations first.

---

## 4. What Changes at 50x Traffic

| Area | At 1x | At 50x | Change needed |
|---|---|---|---|
| CDN / gateway pods | Baseline | 50x requests | Pre-scale gateway pods; raise CDN cache share; stricter per-IP limits; enable queue / waiting-room page if needed |
| Redis | Light | Gate and idempotency ops grow with Buy Now attempts | More Redis shards for idempotency and rate-limit keys (hash by key); stock counter stays one key |
| Inventory MySQL writes | about 100 | about 100 (unchanged) | None |
| Payment calls | about 100 | about 100 (unchanged) | None |
| Orders | about 95 | about 95 (unchanged) | None |
| Kafka events | Baseline | Roughly unchanged for business events (bounded by stock) | None; only analytics/click events grow, and they use a separate topic and cluster |
| Cost | Baseline | Higher only at edge and cache | Scale down after the sale |
| Abuse / bots | Present | Much more likely | WAF bot rules, CAPTCHA or token-based access at Buy Now, per-user limits |

**Optional waiting room:** if demand exceeds what the edge can absorb, users enter a virtual queue and receive a signed token that allows Buy Now. This turns a spike into a controlled flow at the cost of some user friction.

---

## 5. Availability Targets and Deployment

| Item | Design |
|---|---|
| Availability target | 99.9% overall; purchase path (gateway, inventory, payment, checkout) 99.95% during the sale window |
| Multi-AZ | Every stateless service at least 3 replicas across 2-3 availability zones; MySQL primary plus replica in another AZ; Redis with replica; Kafka brokers spread across zones |
| Disaster recovery | Cross-region backups; RTO under 30 minutes, RPO of seconds for MySQL (semi-synchronous replica) |
| Deploy safety | No deployments during the sale (change freeze); canary or blue-green otherwise |
| Graceful degradation order | 1) turn off recommendations and analytics, 2) serve stale stock numbers, 3) enable waiting room, 4) pause the sale (feature flag) |

---

## 6. Failure Handling and Recovery

| Failure | Detection | Automatic response | Guarantee |
|---|---|---|---|
| Service pod crash | Health check, LB removes pod | Restart / replace; retry with idempotency key | No lost or duplicate action |
| Redis down | Gate call fails | **Fail closed** ("try again"); rebuild counter from MySQL available; reopen | Never oversell |
| Redis stale or drifted | Reconciler compares counter with MySQL available | Correct to MySQL truth (never higher) | DB conditional update protects stock |
| MySQL primary failure | Replication / health monitor | Failover to replica (about 30-60 s); in-flight requests retry with same idempotency key | Unique keys prevent double reservation; outbox resumes |
| Payment gateway down | Circuit breaker opens (50% of 10 calls) | Fail fast `503` + `Retry-After`; keep reservation; reconciler resolves unknown payments | No guess-based release; no double charge |
| Payment answer lost | TIMEOUT state | Query gateway by `payment_id`, webhook; grace period on reservation | Exactly-once resolution |
| Kafka down | Publish lag, outbox backlog | Outbox keeps rows and retries; synchronous REST calls continue | No event lost |
| Order Service down (30 s test) | Consumer lag alert | `ConfirmOrder` waits in topic; consumer catches up; saga watchdog resends after 60 s | Every paid order becomes CONFIRMED or is refunded |
| Poison message | 3 retries failed | Dead-letter topic, alert, operator replay | Partition not blocked |
| Whole AZ lost | LB and cluster health | Traffic shifts to other AZs; capacity pre-provisioned at N+1 | Reduced capacity, not outage |

### Reliability mechanisms summary

| Mechanism | Setting |
|---|---|
| Gateway timeout / retry | 3 s per call, 3 attempts, exponential backoff with jitter, only timeout and 5xx |
| Circuit breaker | Open at 50% failures over 10 calls; 30 s open; 3 half-open probes |
| Bulkhead | Separate thread pool and connection limit per downstream dependency |
| Service-to-service calls | Timeout 500 ms-2 s, one retry, fall back to saga/outbox where possible |
| Outbox + inbox | At-least-once publish, idempotent consume by `eventId` |
| Dead-letter topics | After 3 failed attempts or immediate for non-retryable errors |
| Reconcilers | Redis vs MySQL stock; payment TIMEOUT; PaymentSucceeded without confirmed order |
| Saga watchdog | Resends stuck commands after 60 s; alerts after repeated failure |
| Reservation expiry job | Every few seconds, small batches, conditional update; target release within 30 s |

---

## 7. Trade-offs Accepted

| Choice | What we gain | What we sacrifice or complicate |
|---|---|---|
| Redis gate in front of MySQL | DB sees about 100 writes; sold-out answers in under 50 ms | Two stores to align; reconciler needed; fail-closed during Redis outage |
| Eventual consistency between services | No lost payments when a service is down | Customer may see "payment received, confirming order" for a short time |
| CDN-cached sale and stock data | Absorbs 85% of traffic cheaply | Stock display can be a few seconds stale |
| Fail closed on Redis loss | Never oversell | Short loss of sales availability |
| Pre-scaling before the sale | Predictable performance | Cost and need to know sale time in advance |
| Waiting room (optional) | Protects backend from extreme spikes | User friction, extra component |
| Database per service | Independent scaling and failure isolation | No cross-service joins or transactions; saga complexity |
