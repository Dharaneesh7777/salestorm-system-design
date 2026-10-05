Stage 1: Requirement Analysis + Architecture Choice

Spring Boot + Kafka + MySQL + Redis nalla combo, indha problem-ku exact-a fit aagum. Mudhalla architecture sollren, apparam Stage 1 content.

1. Architecture: Event-Driven Microservices + Saga + Redis Gate
Decision	Choice	Reason
Style	5-6 Spring Boot services (event-driven)	Inventory, Payment, Order independent-a scale aaganum, jury "service boundary" kekkum
Inventory contention	Redis Lua atomic gate + MySQL conditional update	Redis 10,000 requests-a fast-a filter pannum, MySQL final truth
Distributed workflow	Saga (orchestrated by Checkout)	Payment success aagi Order fail aana compensation/retry
Reliability	Transactional Outbox + Kafka	DB commit-um event publish-um lose aagaama irukkum
Duplicate handling	Idempotency-Key (Redis + unique DB constraint)	2% duplicate requests-a handle pannum
Gateway	Spring Cloud Gateway + Redis rate limiter	Throttling at entry

Services:

API Gateway (auth, rate limit)
Product/Sale Service (catalogue, cached reads)
Inventory-Reservation Service (core: reserve, confirm, release, expiry)
Checkout Service (Facade/Saga orchestrator)
Payment Service (mock gateway Adapter + Circuit Breaker)
Order Service (state machine)
Notification/Shipment (Kafka consumer, Observer)

Concurrency approaches (Stage 3-ku compare pannanum):

Pessimistic (SELECT ... FOR UPDATE): correct, but 10,000 threads oru row-ku wait, DB bottleneck.
Optimistic (version column): retry storm, last units-ku adhigam fail aagum.
Selected: Redis Lua DECR if stock > 0 (atomic, single-threaded), then MySQL UPDATE inventory SET available_quantity = available_quantity - 1, reserved_quantity = reserved_quantity + 1 WHERE product_id = ? AND available_quantity > 0. Redis fast filter, MySQL guarantee. Redis crash aanaalum MySQL constraint (CHECK available_quantity >= 0) oversell block pannum.
2. Stage 1 Document
A. Functional Requirements
ID	Requirement
FR1	Customer sale products-a browse/search pannalam (cached)
FR2	Cart-la add/update/remove
FR3	"Buy Now" → inventory check + temporary reservation (TTL-oda)
FR4	Oru customer-ku oru product-ku max 1 active reservation (configurable limit)
FR5	Reservation expire aanaa / payment fail aanaa auto-release
FR6	Checkout: price, coupon/deal apply
FR7	Payment initiate, success/failure/timeout handle
FR8	Payment success → Order create → CONFIRMED
FR9	Order lifecycle: CREATED → PAYMENT_PENDING → CONFIRMED → PROCESSING → SHIPPED → OUT_FOR_DELIVERY → DELIVERED
FR10	Cancellation + refund flow
FR11	Notification (order confirmed, payment failed, expired)
FR12	Delivery tracking
FR13	Sold-out aana udane clear response (409 SOLD_OUT)
FR14	Duplicate request-ku same result return (idempotent)
FR15	Admin: sale configure, stock load, audit view
B. Non-Functional Requirements (Measurable)
Category	Requirement	Type
Throughput	Normal 10,000 req/s; flash-sale design reasoning 500,000 req/s at edge	Target
	10,000 concurrent Buy requests handle (single product)	Target
Latency	Product read (cache hit) p95 < 100 ms	Target
	Reservation API p99 < 300 ms	Target
	Sold-out rejection p99 < 50 ms (Redis only)	Target
	Payment confirm → Order CONFIRMED < 5 s normal, < 60 s during Order Service outage	Target
Availability	Browse 99.95%, Checkout/Payment 99.9%	Target
Consistency	Successful sales ≤ 100 (zero oversell)	Strict
	Inventory never negative	Strict
	Oru idempotency key = max oru reservation/payment/order	Strict
	Every payment-success eventually order (or refund)	Strict (eventual)
	Product catalogue/stock display count slightly stale irukkalam	Target (eventual)
Reservation	TTL 5 min; expired release within 30 s of expiry	Target
Security	JWT auth, TLS everywhere, per-user rate limit (e.g., 5 buy/min), no raw card data stored (tokenised), audit log	Strict
Recovery	RPO = 0 for inventory/payment/order data; RTO < 5 min	Target
	Payment gateway timeout: 3 retries, exponential backoff, circuit breaker open after 50% failure in 10 calls	Target
	Failed Kafka messages → DLQ after 3 retries	Target
Observability	Trace ID every request; alert on queue lag > 10 s, inventory mismatch, payment failure > 10%	Target
C. Assumptions
Single product (Product X), 100 units, one warehouse, one region.
Payment gateway external mock (95% success, 5% fail, occasional timeout).
Users already authenticated (login before sale starts).
Reservation TTL 5 min; payment window within TTL.
Failed/expired units pool-ku thirumba varum, so waitlist-a sold-out-nu declare pannama "temporarily unavailable" nu sollalam.
Local demo: Docker-la Kafka, MySQL, Redis; scale numbers simulation + capacity reasoning moolama prove pannuvom.
D. Constraints
Hackathon time (7 hours): critical modules (Inventory, Payment, Order) matum detail-a.
MySQL single instance (docker), so production-la replicas/sharding paththi design-la solluvom.
Real payment gateway illa, so Adapter + mock.
E. Traffic Estimation (funnel-a explain pannu)
500,000 req/s at edge
 → CDN/WAF absorb (static, product page)     ~80-90%
 → Gateway rate limit / bot filter           
 → ~10,000 Buy Now requests reach Reservation (10k users)
 → Redis Lua gate: 100 pass, 9,900 rejected in < 50 ms
 → ~100 MySQL conditional updates (not 10,000!)
 → ~95 payments succeed, ~5 fail → released → re-opened

Key point: MySQL-ku 10,000 request poga koodadhu, athaan namma core design argument.

F. Critical Dependencies

Redis (gate, idempotency, rate limit), MySQL (source of truth), Kafka (events/saga), Payment Gateway (external), API Gateway/CDN, Notification provider.

G. Strict Guarantees vs Targets
Strict (never violate): no oversell, no negative stock, no duplicate payment/order, no lost paid order, secure payment data.
Targets (SLO, degrade allowed): latency numbers, availability %, notification delay, stock-count freshness on UI, 500k rps throughput.

Trade-off to mention: Redis gate add pannadhala complexity + Redis/MySQL drift risk varum. Adha reconciliation job (periodic Redis ↔ MySQL sync) + MySQL constraint moolama handle pannurom.