# SALESTORM – Stage 8: Architecture Decision Record (ADR)

**System:** High-scale flash-sale e-commerce platform. **Reference scenario:** 100 units, 10,000 concurrent buyers, 95% payment success, 2% duplicate requests, Order Service down for 30 s, flash-sale reasoning up to 500,000 requests/second at the edge.

**Stack in use:** Spring Boot services, MySQL, Redis, Kafka (Docker for the demo; clustered equivalents in production reasoning).

**Format of every ADR:** Context → Options → Decision → Why for SALESTORM → Consequences (what we gain, what we sacrifice) → Revisit when.

---

## 1. ADR Index

| ADR | Decision | Status |
|---|---|---|
| 001 | Event-driven services (not a monolith) | Accepted |
| 002 | MySQL (SQL) as source of truth, not NoSQL | Accepted |
| 003 | Concurrency control: Redis atomic gate + MySQL conditional update | Accepted |
| 004 | Temporary reservation with TTL (not decrement-on-payment) | Accepted |
| 005 | Hybrid sync/async communication | Accepted |
| 006 | Kafka as the messaging backbone | Accepted |
| 007 | Orchestrated Saga (not 2PC, not pure choreography) | Accepted |
| 008 | Transactional Outbox + idempotent consumers | Accepted |
| 009 | Idempotency keys at API, DB and message level | Accepted |
| 010 | Cache strategy by data type | Accepted |
| 011 | Redis failure mode: fail closed | Accepted |
| 012 | Overload handling: shed at the edge and at the gate | Accepted |
| 013 | Reservation expiry driven by database state | Accepted |
| 014 | Payment resilience: timeout, retry, circuit breaker, reconcile | Accepted |
| 015 | Database per service (schema per service in the demo) | Accepted |
| 016 | Consistency positioning (CAP) per capability | Accepted |
| 017 | Order record created at Buy Now, asynchronously | Accepted |

---

## ADR-001: Event-driven services instead of a monolith

**Context:** Inventory, Payment and Order have different scaling, failure and consistency needs. Payment depends on an unreliable external gateway; Inventory takes the burst; Order must survive downstream outages.

| Option | Pros | Cons |
|---|---|---|
| Single monolith | Simplest, one transaction, easy local debugging | One failure or hot path takes everything down; cannot scale Inventory separately; coarse blast radius |
| Modular monolith | Simple deployment, clear modules | Still scales as one unit; payment gateway slowness shares threads and pool with inventory |
| **Services with async events (selected)** | Independent scaling and failure isolation; clear ownership of invariants | Distributed-system complexity, eventual consistency, more to operate |

**Decision:** 6 to 7 Spring Boot services (Gateway, Product/Sale, Inventory-Reservation, Checkout, Payment, Order, Notification/Shipment) with Kafka events.

**Why for SALESTORM:** the burst lands on Inventory only, payment failures must not stop browsing, and orders must survive an Order Service outage. Services also match the jury's "service boundary" question: one owner per invariant.

**Consequences:** (+) fault isolation, targeted scaling. (−) eventual consistency, saga and outbox needed, harder testing. For the demo only Inventory, Checkout, Payment and Order are built in full; the rest are thin.

**Revisit when:** team is very small and traffic is low (a modular monolith would then be preferable).

---

## ADR-002: SQL (MySQL) as source of truth vs NoSQL

**Context:** Stock, reservations, payments and orders require strong consistency, constraints and multi-row atomic updates (reservation row plus inventory counters plus outbox row).

| Criterion | **MySQL (SQL)** | Document store (e.g. MongoDB) | Wide-column / key-value (e.g. Cassandra, DynamoDB) |
|---|---|---|---|
| Multi-row ACID transaction | Strong, mature | Supported but narrower and costlier | Limited or conditional-write only |
| Constraints (unique, check, foreign key) | Native | Mostly application-enforced | Mostly application-enforced |
| Conditional atomic update on one row | Yes | Yes | Yes (single item) |
| Horizontal write scale | Needs replicas, partitioning, sharding | Better | Best |
| Ad-hoc queries and reporting | Excellent | Good | Poor |
| Team familiarity / already running | Yes | No | No |
| Oversell protection by constraint | `CHECK` on non-negative quantity, unique idempotency key | Weaker, application logic | Weaker |

**Decision:** MySQL (InnoDB) is the source of truth for inventory, reservation, payment, order and outbox. NoSQL is not used for these.

**Why for SALESTORM:** the critical guarantee (never sell more than 100) is a **correctness** problem, not a storage-volume problem. Redis already removes the write burst, so MySQL sees about 100 reservation writes for 10,000 requests. SQL constraints are the last line of defence.

**Consequences:** (+) integrity, simple reasoning, easy audit. (−) vertical limits on a single primary; at far larger scale needs read replicas, partitioning by product or sale, or sharding.

**Possible later use of NoSQL:** product catalogue search or read models, event archives and analytics, not the transactional core.

**Revisit when:** write volume per product grows beyond what one primary can handle after Redis shedding, or multi-region active-active writes become a requirement.

---

## ADR-003: Concurrency control for inventory

**Context:** 10,000 customers hit one product row for 100 units. We must never oversell and never go negative.

| Option | How | Pros | Cons for this problem |
|---|---|---|---|
| A. Pessimistic lock (row lock) | First request locks; others wait | Simple, correct | 10,000 threads queue on one row; pool exhaustion; high latency; DB is the bottleneck |
| B. Optimistic lock (version column) | Read, then update if version unchanged | No long locks | Near stock end almost every attempt conflicts; retry storm multiplies DB load; poor fairness |
| C. Atomic conditional DB update only | Single statement decrement where available > 0 | Correct, simple | Still 10,000 statements on one hot row |
| D. Distributed lock (e.g. Redis lock) then DB write | Lock per product | Serialises writers | Lock expiry and failure edge cases; still adds lock traffic; correctness depends on lock safety |
| **E. Redis atomic gate + MySQL conditional update (selected)** | Redis Lua check-and-decrement admits about 100; DB conditional update plus constraint finalises | Sold-out rejection in under 50 ms; DB sees about 100 writes; DB guards correctness even if Redis is wrong | Two stores to keep aligned; reconciliation needed |

**Decision:** Option E.

**Why for SALESTORM:** the real problem is contention on one hot key. E serialises cheaply in memory and protects the database; correctness never depends on Redis because the DB conditional update and non-negative constraint decide the final outcome.

**Consequences:**
- (+) 100x fewer DB writes, low latency, deterministic last-item behaviour.
- (−) Redis and MySQL can drift. We make Redis only ever **lower than or equal to** MySQL (conservative), run a reconciler, and rebuild the counter from MySQL after a restart.
- (−) Extra component to run and monitor.

**Revisit when:** products multiply (shard Redis keys by product) or a single Redis shard becomes the bottleneck.

---

## ADR-004: Temporary reservation with TTL

**Context:** Payment takes seconds and can fail. Stock must be held fairly during payment but must come back if the customer does not pay.

| Option | Pros | Cons |
|---|---|---|
| Decrement stock only after payment | Never holds stock for non-payers | Customers pay for items that are then unavailable; refund storms; unfair |
| Decrement at Buy Now, no expiry | Simple | Abandoned or failed payments lock stock forever |
| **Reserve with TTL (5 min) then confirm or release (selected)** | Fair, no oversell, stock recovers | More states and an expiry process |

**Decision:** Reservation lifecycle RESERVED → PAYMENT_PENDING → CONFIRMED → SOLD, with RELEASED on failure or expiry. TTL 5 minutes, extended by a grace period (up to 10 minutes in total) only while a payment outcome is unknown.

**Why for SALESTORM:** the brief requires reservation with expiry and release; a 5% payment failure rate means about 5 units come back and must be resellable.

**Consequences:** (+) fairness, recovery. (−) "sold out" may be temporary, so we show `TEMPORARILY_UNAVAILABLE` while unpaid holds exist; late payment success needs the re-reserve or refund rule.

---

## ADR-005: Synchronous vs asynchronous communication

| Interaction | Choice | Reason |
|---|---|---|
| Buy Now to Inventory reserve | **Sync** | Customer needs an immediate decision |
| Pay to Payment and gateway | **Sync with bounded wait (3 s), falls back to 202 and status polling** | Customer waits on the payment page, but a slow gateway must not hold threads |
| Payment result to Inventory, Checkout | **Async** | Decouples payment latency and failure |
| Order creation, confirmation, cancellation | **Async commands** | Must survive an Order Service outage |
| Refunds | **Async** | Money movement is eventual and retried |
| Notification, shipment | **Async** | Must never block the purchase |

**Alternatives:** fully synchronous (simple, but any slow downstream blocks customers and an Order outage fails paid purchases) or fully asynchronous (resilient, but the customer would get no immediate answer for "did I get the item?").

**Decision:** hybrid. Rule: **if the customer is waiting for an authoritative decision, it is synchronous; if it can be retried later without the customer noticing, it is asynchronous.**

**Consequences:** (+) fast answers where needed, resilience elsewhere. (−) two communication styles to understand; customers see interim states ("payment received, confirming order").

---

## ADR-006: Kafka as the messaging backbone

| Option | Pros | Cons |
|---|---|---|
| **Kafka (selected)** | Durable log, replay, partition ordering per key, high throughput, consumer groups; already available in the environment | Heavier operations, partition planning |
| RabbitMQ | Flexible routing, simple queues, lower operational weight | No native replay of history, ordering weaker at scale |
| Cloud queue (SQS/SNS) | Managed | Less ordering and replay control, vendor tie-in |
| Database polling only | Minimal infrastructure | Poor scalability, latency, load on the primary DB |

**Decision:** Kafka. Partition key `orderId` for per-purchase ordering. Topics owned by one producer each (see `05_API/event_design.md`).

**Why for SALESTORM:** durable buffering is exactly what protects paid orders during a 30 s Order Service outage; replay supports reconciliation and dead-letter recovery.

**Consequences:** (+) durability, replay, scalable consumers. (−) at-least-once delivery means every consumer must be idempotent; operational complexity; ordering only within a partition.

---

## ADR-007: Distributed workflow: Saga (orchestrated)

| Option | Pros | Cons |
|---|---|---|
| Two-phase commit across services/DBs | Atomic across services | Blocking, poor availability, not supported by Kafka or external gateways |
| Choreography only (events, no coordinator) | Loosely coupled | Flow scattered across services; hard to explain, test and debug a compensation chain |
| **Orchestrated saga in Checkout (selected)** | One place that knows the flow and compensation; easy to trace and defend | Orchestrator is an extra component; risk of becoming a god service |

**Decision:** Checkout owns a saga record and sends commands to Order; Inventory and Payment react to events (a hybrid), with compensation published as `CheckoutFailed`.

**Why for SALESTORM:** the jury asks "payment succeeded but Order Service failed: what happens?" The saga plus watchdog gives one clear answer: resend, reconcile, then compensate (refund and release).

**Consequences:** (+) clarity, observability of saga state. (−) compensation logic must be designed and tested; the orchestrator is kept thin (no business data of other services).

---

## ADR-008: Transactional Outbox + idempotent consumers

**Context:** "Update the database" and "publish an event" are two systems; either can fail alone.

| Option | Pros | Cons |
|---|---|---|
| Dual write (save then publish) | Simple | Crash between the two loses or fabricates events |
| Publish inside the DB transaction (two-phase) | Atomic in theory | Not practical with Kafka |
| **Outbox table + publisher (selected)** | Atomic with the business change; no lost events | Slight latency; at-least-once duplicates; extra table and job |
| CDC (Debezium) on the outbox | Removes polling job, lower latency | Extra infrastructure (Kafka Connect); more to operate |

**Decision:** Outbox written in the same transaction as the state change; a publisher sends rows to Kafka with an idempotent producer and `acks=all`. Consumers use an inbox (`eventId` unique) so a replay is harmless. CDC is the production upgrade for the publisher.

**Consequences:** (+) no lost events, safe replays. (−) duplicates must be tolerated; outbox backlog needs monitoring.

---

## ADR-009: Idempotency at every layer

**Context:** retries, double clicks, gateway callbacks and Kafka redelivery all produce duplicates (2% in the test case).

| Option | Pros | Cons |
|---|---|---|
| Rely on clients not to repeat | None | Unsafe |
| Cache-only dedup (Redis) | Fast | Lost on Redis failure |
| DB unique constraint only | Durable | Slower, poor response for in-progress requests |
| **Layered: client key + Redis fast path + DB unique constraints + gateway reference + inbox (selected)** | Fast and durable; each layer covers the others' failure | More mechanisms to maintain |

**Decision:** `Idempotency-Key` on Buy, Pay, Cancel; Redis stores results; DB has unique keys and the one-active-reservation rule; `paymentId` is the gateway's merchant reference; consumers dedupe by `eventId`; state machines ignore repeated or stale transitions.

**Why for SALESTORM:** duplicate charging and duplicate reservations are the two failures that would be most visible and costly.

**Consequences:** (+) repeated requests are safe. (−) key retention (24 h in Redis) and client discipline (new key for a fresh attempt); same key with a different body is rejected.

---

## ADR-010: Cache strategy

**Context:** the flash sale is read-heavy (browsing) with one extremely hot write path (stock). Different data needs different cache behaviour.

| Strategy | Description | Fit |
|---|---|---|
| **Cache-aside (lazy loading)** | App reads cache, falls back to DB, then populates | Product and sale details (read-heavy, rarely changing) |
| Read-through | Cache layer loads from DB itself | Similar to cache-aside; needs special cache provider |
| Write-through | Writes go to cache and DB together | Possible for config; adds write latency |
| Write-behind | Writes go to cache, DB updated later | **Rejected for inventory, payment, orders:** risk of data loss on cache failure |
| No cache | Always DB | Fine for transactional data |
| CDN edge cache | Static assets, public product pages | Offloads most of the 500,000 rps |

**Decision by data type:**

| Data | Strategy | TTL / invalidation | Notes |
|---|---|---|---|
| Static content and images | CDN | Long | Absorbs most edge traffic |
| Product and sale details | Cache-aside in Redis (plus CDN) | Short (for example 30 to 60 s); invalidated by admin update | Protects MySQL from read bursts; cache stampede guarded by request coalescing and TTL jitter |
| Displayed stock status (`IN_STOCK`, `LOW_STOCK`, `SOLD_OUT`) | Cache updated from `StockLevelChanged` events | Event-driven, may be stale by seconds | Display only; never used for reservation decisions |
| **Stock gate counter** | **Redis as a gate, not a cache** | Rebuilt from MySQL; reconciled periodically | Authoritative decision is still the DB conditional update |
| Idempotency keys | Redis with 24 h TTL | TTL | Backed by DB unique constraints |
| Rate-limit counters | Redis | Sliding window | Gateway enforced |
| Orders, payments, reservations | **Not cached** | n/a | Correctness over speed; read from DB or replica |

**Why for SALESTORM:** staleness is acceptable for display (the brief's catalogue/stock freshness is a target) but never for stock decisions.

**Consequences:** (+) edge and DB protection. (−) stale display counts (customer may see "in stock" and receive `SOLD_OUT`), cache invalidation and stampede handling to maintain.

---

## ADR-011: Redis failure mode: fail closed

**Context:** if Redis is down, the gate cannot filter, and 10,000 requests would hit MySQL.

| Option | Behaviour | Risk |
|---|---|---|
| Fail open (bypass Redis, go straight to DB) | Sales continue | Database overload; contention collapse; possible cascading failure |
| **Fail closed (selected)** | Return `503 SERVICE_UNAVAILABLE`, ask client to retry with the same key | Temporary loss of sales availability |
| Local in-memory fallback gate per pod | Some continuity | Per-pod counters cannot represent global stock; risk of over-admission (still blocked by DB) |

**Decision:** fail closed with clear retry messaging. Redis runs as a replicated cluster (primary plus replica with automatic failover) to make this rare. After recovery, the counter is rebuilt from MySQL before the gate reopens.

**Why for SALESTORM:** correctness and database survival matter more than a few seconds of availability; overselling is impossible either way, but database collapse would hurt everyone, including already-paying customers.

**Consequences:** (−) short availability dip for Buy Now only; browsing, payment and orders in progress are unaffected.

---

## ADR-012: Overload handling: shed at the edge and at the gate

**Context:** up to 500,000 requests/second at the edge, only 100 units, about 10,000 actual buyers.

| Option | Pros | Cons |
|---|---|---|
| Accept everything and queue all requests (queue-based reservation) | Fair ordering, no rejections | Huge queue, long waits for people who cannot win, memory and latency risk |
| **Shed early: CDN/WAF, gateway rate limits, Redis gate (selected)** | Cheap rejection, stable system | Some customers get quick `SOLD_OUT` or `429` |
| Virtual waiting room | Fair entry, smooths bursts | Extra component and UX |

**Decision:** layered shedding: CDN/WAF absorbs static and bot traffic; gateway enforces per-user and per-IP limits (5 Buy per minute per user); Redis gate rejects surplus in under 50 ms. At 50x traffic a **virtual waiting room** in front of checkout is added to limit entrants.

**Consequences:** (+) database load is independent of traffic volume. (−) legitimate users may see `429`; fairness is first-come at the gate rather than strict queue order.

---

## ADR-013: Reservation expiry driven by database state

| Option | Pros | Cons |
|---|---|---|
| Redis key expiry notifications | Convenient | Notifications are best-effort and can be missed; not safe for stock correctness |
| Kafka delayed messages | Event-style | Kafka has no native delay; extra machinery |
| **Scheduler scanning `expires_at` in MySQL (selected)** | Durable, restart-safe, idempotent, simple to reason about | Poll interval adds a few seconds of delay; scan cost |

**Decision:** an `ExpiryScheduler` runs every few seconds, picks expired RESERVED or grace-expired PAYMENT_PENDING rows in small batches (index on `status`, `expires_at`), and releases each in its own transaction with a conditional status update (only one worker wins). The Redis counter is credited after commit. Target: released within 30 s of expiry.

**Consequences:** (+) no lost expirations, even after a crash. (−) release not instantaneous; batch size and interval must be tuned to avoid a release storm.

---

## ADR-014: Payment resilience

| Concern | Decision | Alternative rejected |
|---|---|---|
| Slow gateway | Timeout 3 s per call | Waiting indefinitely holds threads |
| Transient failures | Up to 3 retries, exponential backoff with jitter, **same `paymentId`** | Retrying with new ids risks double charge |
| Gateway outage | Circuit breaker opens at 50% failures over 10 calls; 30 s open; 3 half-open probes; bulkhead pool | No breaker leads to cascading failures |
| Unknown outcome | State `TIMEOUT`, reservation grace, reconcile by status query and webhook | Treating timeout as failure could release stock for a customer who was actually charged |
| Decline | Final answer, no retry | Retrying declines is pointless and risky |
| While circuit open | Fail fast `503`, keep reservation | Releasing immediately would punish customers for our dependency's outage |

**Consequences:** (+) no double charge, no guessing about money. (−) customers may see "being verified"; reconciler and webhook add operational surface; long gateway outages can exhaust the reservation TTL, so stock returns and customers must retry.

---

## ADR-015: Database per service

| Option | Pros | Cons |
|---|---|---|
| Shared database for all services | Simple joins and transactions | Hidden coupling, shared locks and schema, no real service boundaries |
| **Database (schema) per service (selected)** | Clear ownership, independent change, enforces Kafka-based integration | No cross-service joins or transactions; data duplication in read models |

**Decision:** each service owns its schema and is its only reader and writer. For the Docker demo all schemas live in one MySQL instance with separate users and schemas; in production each service has its own cluster.

**Consequences:** (+) boundaries are real and defensible. (−) reporting needs events or a read model; consistency across services is eventual (saga).

---

## ADR-016: Consistency positioning (CAP) per capability

| Capability | Priority | Behaviour during partition or failure |
|---|---|---|
| Inventory and reservation | **Consistency over availability** | Fail closed rather than risk oversell |
| Payment | **Consistency** (never double charge, never lose a charge) | Fail fast and reconcile |
| Order lifecycle | Eventual consistency with guaranteed completion | Commands wait in Kafka; paid orders always end confirmed or refunded |
| Product browsing and search | **Availability over consistency** | Serve cached or slightly stale data |
| Notifications, tracking | Availability, eventual | Delay is acceptable |
| Analytics | Eventual | Replay from events |

**Why:** the business can tolerate a stale stock label but cannot tolerate a 101st sale or a double charge.

---

## ADR-017: Order record created at Buy Now, asynchronously

**Context:** the required order lifecycle begins at CREATED and passes through PAYMENT_PENDING. An Order Service outage must not block reservation or payment.

| Option | Pros | Cons |
|---|---|---|
| Create order synchronously during Buy Now | Order exists immediately | Puts Order Service on the hot path; its outage blocks purchases |
| Create order only after payment success | Fewer orders for non-payers | State history starts mid-way; payment cancellations have no order to update; does not match CREATED → PAYMENT_PENDING |
| **Generate `orderId` at Buy Now, send `CreateOrder` asynchronously, create-if-absent on `ConfirmOrder` (selected)** | Hot path independent of Order Service; full lifecycle preserved; tolerant of lost or late commands | Order may appear a moment after Buy Now; consumer must handle out-of-order commands |

**Decision:** Checkout generates `orderId`; Order Service creates the order when `CreateOrder` arrives, and `ConfirmOrder` carries enough data to create it if the earlier command never arrived.

**Consequences:** (+) resilience. (−) brief window where status is served from the saga record rather than the Order Service.

---

## 2. What the Architecture Sacrifices (explicit trade-offs)

| We gain | We sacrifice or complicate |
|---|---|
| Zero oversell, no double charge | Availability of Buy Now during Redis or DB failure (fail closed) |
| 10,000 requests handled with about 100 DB writes | Complexity of two stores (Redis gate and MySQL) and a reconciler |
| Order survives a 30 s Order Service outage | Immediate consistency: customers see interim states |
| Independent scaling and fault isolation | Operational overhead of 6 to 7 services, Kafka, Redis, saga, outbox |
| Fast reads under 500,000 rps | Slightly stale stock and product display |
| Safe retries everywhere | Duplicate handling in every consumer; key retention rules |
| Clean extension points (patterns) | More classes and contract tests |
| Fairness through reservations | Stock can look sold out while unpaid holds exist (`TEMPORARILY_UNAVAILABLE`) |

---

## 3. Hackathon Scope vs Production Scope

| Topic | Demo (Docker, 7 hours) | Production reasoning (defended in jury) |
|---|---|---|
| MySQL | Single instance, schema per service | Primary plus replicas, per-service clusters, partitioning or sharding by product or sale |
| Redis | Single node | Cluster with replicas and failover, shard by product |
| Kafka | Single broker | 3 or more brokers, replication factor 3, more partitions |
| Services | Inventory, Checkout, Payment, Order in full; Gateway and Notification thin | All services, autoscaled |
| Outbox publisher | Scheduled poller | CDC (Debezium) |
| Payment gateway | Mock adapter (95% success, 5% failure, timeouts) | Real providers behind the same interface |
| Scale proof | Simulation plus capacity reasoning | Load tests on a staging cluster |

---

## 4. Jury Question to ADR Map

| Jury question | Answer in |
|---|---|
| Why is this the correct service boundary? | ADR-001, ADR-015 |
| Where exactly is inventory consistency guaranteed? | ADR-002, ADR-003 |
| What happens if two requests reach Inventory at the same time? | ADR-003 |
| Why this concurrency strategy? | ADR-003 |
| Why is this operation synchronous and that one asynchronous? | ADR-005 |
| Recovery from payment success followed by order failure? | ADR-007, ADR-008, ADR-017 |
| Likely bottleneck and how it scales? | ADR-003, ADR-010, ADR-012 |
| SQL or NoSQL? | ADR-002 |
| What happens if Redis or the gateway fails? | ADR-011, ADR-014 |
| What changes at 50x traffic? | ADR-012, ADR-010 |
