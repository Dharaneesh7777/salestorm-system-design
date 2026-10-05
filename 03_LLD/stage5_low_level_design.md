# SALESTORM – Stage 5: Low-Level Design (LLD)

**Critical modules selected:** Inventory-Reservation, Payment, Order (plus the Checkout Facade that coordinates them).
**Goal:** clear responsibilities, small interfaces, explicit state, and a clear home for validation, idempotency and error handling. Not a large codebase.

---

## 1. Service Boundaries and Domain Responsibilities

| Service | Domain responsibility (owns) | Must NOT do | Data it owns |
|---|---|---|---|
| **Inventory-Reservation** | Stock buckets, reservation lifecycle, expiry, oversell protection | Take payments, create orders, know price | `inventory`, `inventory_reservation`, `outbox` |
| **Payment** | Charging, refunds, payment state, payment idempotency, gateway isolation | Change stock or order state directly | `payment`, `refund`, `outbox` |
| **Order** | Order record, order state machine, order history, cancellation rules | Charge money, touch stock counters | `orders`, `order_item`, `order_status_history`, `outbox` |
| **Checkout** | Orchestrates the purchase saga, applies pricing/coupon, tracks saga progress | Persist stock, payments or orders itself | `checkout_saga`, `outbox` |
| **Notification / Shipment** | Messages and fulfilment tracking driven by events | Any purchase decision | `notification`, `shipment` |

**Why these boundaries are right (jury answer):** each service owns exactly one reason to change and one set of invariants. Stock correctness lives only in Inventory (single writer). Money correctness lives only in Payment. Lifecycle correctness lives only in Order. They talk through commands and events, so any one can fail or scale independently.

---

## 2. Class Diagrams

### 2.1 Inventory-Reservation module

```mermaid
classDiagram
    direction LR

    class ReservationController {
        +reserve(ReserveRequest) ReservationResponse
        +getReservation(id) ReservationResponse
    }
    class ReservationService {
        +reserve(ReserveCommand) ReservationResult
        +markPaymentPending(reservationId)
        +confirm(reservationId)
        +markSold(reservationId)
        +release(reservationId, reason)
    }
    class IdempotencyStore {
        <<interface>>
        +begin(key, requestHash) IdempotencyStatus
        +complete(key, result)
        +find(key) StoredResult
    }
    class RedisIdempotencyStore
    class StockGate {
        <<interface>>
        +tryAcquire(productId, qty) boolean
        +giveBack(productId, qty)
        +rebuild(productId, available)
    }
    class RedisStockGate
    class InventoryRepository {
        <<interface>>
        +reserveUnits(productId, qty) boolean
        +releaseUnits(productId, qty) boolean
        +markSold(productId, qty) boolean
    }
    class ReservationRepository {
        <<interface>>
        +save(Reservation)
        +findById(id) Reservation
        +findExpired(now, batch) List
        +hasActiveFor(customerId, productId) boolean
    }
    class Reservation {
        -reservationId
        -productId
        -customerId
        -expiresAt
        -idempotencyKey
        -state ReservationState
        +confirm()
        +markPaymentPending()
        +markSold()
        +release(reason)
        +isExpired(now) boolean
    }
    class ReservationState {
        <<interface>>
        +confirm(Reservation)
        +markPaymentPending(Reservation)
        +markSold(Reservation)
        +release(Reservation, reason)
    }
    class ReservedState
    class PaymentPendingState
    class ConfirmedState
    class SoldState
    class ReleasedState
    class OutboxWriter {
        <<interface>>
        +add(DomainEvent)
    }
    class ExpiryScheduler {
        +releaseExpired()
    }
    class PaymentEventConsumer {
        +onPaymentSucceeded(event)
        +onPaymentFailed(event)
        +onOrderConfirmed(event)
    }
    class StockReconciler {
        +reconcile(productId)
    }

    ReservationController --> ReservationService
    ReservationService --> IdempotencyStore
    ReservationService --> StockGate
    ReservationService --> InventoryRepository
    ReservationService --> ReservationRepository
    ReservationService --> OutboxWriter
    IdempotencyStore <|.. RedisIdempotencyStore
    StockGate <|.. RedisStockGate
    ReservationRepository --> Reservation
    Reservation o-- ReservationState
    ReservationState <|.. ReservedState
    ReservationState <|.. PaymentPendingState
    ReservationState <|.. ConfirmedState
    ReservationState <|.. SoldState
    ReservationState <|.. ReleasedState
    ExpiryScheduler --> ReservationService
    PaymentEventConsumer --> ReservationService
    StockReconciler --> StockGate
    StockReconciler --> InventoryRepository
```

**Responsibilities**
- `ReservationService` is the only class that coordinates gate, DB and outbox for stock changes.
- `StockGate` hides Redis; `InventoryRepository` hides MySQL (Repository + DIP). The service never mentions Redis or SQL.
- `Reservation` owns its legal transitions through `ReservationState` (State pattern), so invalid transitions cannot happen anywhere else in the code.
- `ExpiryScheduler`, `PaymentEventConsumer` and `StockReconciler` are **entry points** that call the same service methods, so there is one implementation of each business rule.

### 2.2 Payment module

```mermaid
classDiagram
    direction LR

    class PaymentController {
        +pay(PayRequest, idempotencyKey) PaymentResponse
        +getPayment(id) PaymentResponse
        +refund(paymentId) RefundResponse
    }
    class PaymentService {
        +initiate(PayCommand) PaymentResult
        +applyGatewayResult(paymentId, GatewayResult)
        +refund(paymentId, reason)
    }
    class PaymentIdempotencyGuard {
        +checkOrRegister(key, requestHash) GuardResult
    }
    class PaymentGateway {
        <<interface>>
        +charge(ChargeRequest) GatewayResult
        +refund(RefundRequest) GatewayResult
        +queryStatus(paymentId) GatewayResult
    }
    class MockGatewayAdapter
    class RazorpayAdapter
    class StripeAdapter
    class ResilientPaymentGateway {
        -delegate PaymentGateway
        -circuitBreaker
        -retryPolicy
        -timeout
    }
    class PaymentGatewayFactory {
        +forMethod(method) PaymentGateway
    }
    class Payment {
        -paymentId
        -orderId
        -reservationId
        -amount
        -idempotencyKey
        -status PaymentStatus
        +markProcessing()
        +markSucceeded(gatewayRef)
        +markFailed(reason)
        +markTimeout()
        +requestRefund()
        +markRefunded()
    }
    class PaymentStatus {
        <<enumeration>>
        INITIATED
        PROCESSING
        SUCCEEDED
        FAILED
        TIMEOUT
        REFUND_PENDING
        REFUNDED
    }
    class PaymentRepository {
        <<interface>>
        +save(Payment)
        +findById(id) Payment
        +findByIdempotencyKey(key) Payment
        +findStuck(olderThan) List
    }
    class PaymentReconciler {
        +resolveUnknownPayments()
    }
    class GatewayWebhookController {
        +onCallback(payload)
    }
    class OutboxWriter {
        <<interface>>
        +add(DomainEvent)
    }

    PaymentController --> PaymentService
    PaymentService --> PaymentIdempotencyGuard
    PaymentService --> PaymentGatewayFactory
    PaymentService --> PaymentRepository
    PaymentService --> OutboxWriter
    PaymentGatewayFactory --> PaymentGateway
    PaymentGateway <|.. ResilientPaymentGateway
    PaymentGateway <|.. MockGatewayAdapter
    PaymentGateway <|.. RazorpayAdapter
    PaymentGateway <|.. StripeAdapter
    ResilientPaymentGateway o-- PaymentGateway
    PaymentRepository --> Payment
    Payment --> PaymentStatus
    PaymentReconciler --> PaymentGateway
    PaymentReconciler --> PaymentService
    GatewayWebhookController --> PaymentService
```

**Responsibilities**
- `PaymentGateway` is the abstraction; each provider is an **Adapter** that converts our model to the provider API. A new provider means a new adapter class only.
- `ResilientPaymentGateway` is a **decorator/proxy** adding timeout, retry and circuit breaker around any gateway, so resilience is written once.
- `PaymentService` does not know which provider is used; it asks the factory.
- Webhook and reconciler both feed the same `applyGatewayResult`, which is idempotent.

### 2.3 Order module

```mermaid
classDiagram
    direction LR

    class OrderController {
        +getOrder(id) OrderResponse
        +cancel(id) OrderResponse
        +listMyOrders() List
    }
    class OrderCommandConsumer {
        +onCreateOrder(cmd)
        +onPaymentInitiated(cmd)
        +onConfirmOrder(cmd)
        +onCancelOrder(cmd)
    }
    class OrderService {
        +create(CreateOrderCommand) Order
        +markPaymentPending(orderId)
        +confirm(orderId, paymentId)
        +cancel(orderId, reason)
        +advance(orderId, event)
    }
    class Order {
        -orderId
        -customerId
        -paymentId
        -items
        -totalAmount
        -state OrderState
        +markPaymentPending()
        +confirm(paymentId)
        +startProcessing()
        +ship()
        +outForDelivery()
        +deliver()
        +cancel(reason)
    }
    class OrderState {
        <<interface>>
        +markPaymentPending(Order)
        +confirm(Order, paymentId)
        +startProcessing(Order)
        +ship(Order)
        +outForDelivery(Order)
        +deliver(Order)
        +cancel(Order, reason)
    }
    class CreatedState
    class PaymentPendingState
    class ConfirmedState
    class ProcessingState
    class ShippedState
    class OutForDeliveryState
    class DeliveredState
    class CancelledState
    class OrderStatusHistory {
        -fromState
        -toState
        -reason
        -eventId
        -at
    }
    class OrderRepository {
        <<interface>>
        +save(Order)
        +findById(id) Order
        +findPaidWithoutConfirmation(olderThan) List
    }
    class OrderEventPublisher {
        <<interface>>
        +publish(OrderEvent)
    }
    class OutboxOrderEventPublisher
    class OrderListener {
        <<interface>>
        +onOrderEvent(OrderEvent)
    }
    class NotificationListener
    class ShipmentListener
    class OrderReconciler {
        +repairStuckOrders()
    }

    OrderController --> OrderService
    OrderCommandConsumer --> OrderService
    OrderService --> OrderRepository
    OrderService --> OrderEventPublisher
    OrderRepository --> Order
    Order o-- OrderState
    Order *-- OrderStatusHistory
    OrderState <|.. CreatedState
    OrderState <|.. PaymentPendingState
    OrderState <|.. ConfirmedState
    OrderState <|.. ProcessingState
    OrderState <|.. ShippedState
    OrderState <|.. OutForDeliveryState
    OrderState <|.. DeliveredState
    OrderState <|.. CancelledState
    OrderEventPublisher <|.. OutboxOrderEventPublisher
    OrderListener <|.. NotificationListener
    OrderListener <|.. ShipmentListener
    OrderReconciler --> OrderService
```

**Responsibilities**
- `Order` delegates every transition to its `OrderState`; an illegal call (for example `ship()` on `CreatedState`) throws an invalid-transition error. All transition rules sit in one place.
- Every successful transition appends an `OrderStatusHistory` row in the same transaction.
- `OrderEventPublisher` + `OrderListener` give an **Observer** style fan-out (notification, shipment) without `OrderService` knowing the listeners. Across services, the implementation is the outbox plus Kafka.

### 2.4 Checkout Facade (coordination, kept thin)

```mermaid
classDiagram
    direction LR

    class CheckoutController {
        +buyNow(BuyRequest, idempotencyKey) BuyResponse
        +pay(PayRequest, idempotencyKey) PayResponse
    }
    class CheckoutFacade {
        +buyNow(cmd) BuyResult
        +pay(cmd) PayResult
    }
    class PricingStrategy {
        <<interface>>
        +price(Cart, Customer) Money
    }
    class FlatPricing
    class DealPricing
    class CouponPricing
    class CheckoutSaga {
        -sagaId
        -state SagaState
        +onReserved()
        +onPaymentSucceeded()
        +onPaymentFailed()
        +onOrderConfirmed()
        +onTimeout()
    }
    class InventoryClient {
        <<interface>>
        +reserve(cmd) ReservationResult
    }
    class PaymentClient {
        <<interface>>
        +initiate(cmd) PaymentResult
    }
    class SagaRepository {
        <<interface>>
        +save(CheckoutSaga)
        +findStuck(olderThan) List
    }
    class SagaWatchdog {
        +resendStuckCommands()
    }

    CheckoutController --> CheckoutFacade
    CheckoutFacade --> PricingStrategy
    CheckoutFacade --> CheckoutSaga
    CheckoutFacade --> InventoryClient
    CheckoutFacade --> PaymentClient
    PricingStrategy <|.. FlatPricing
    PricingStrategy <|.. DealPricing
    PricingStrategy <|.. CouponPricing
    CheckoutSaga --> SagaRepository
    SagaWatchdog --> CheckoutSaga
```

---

## 3. Sequence Diagrams

### 3.1 Purchase / Reservation

```mermaid
sequenceDiagram
    autonumber
    participant C as Customer
    participant GW as API Gateway
    participant CH as Checkout
    participant IV as Inventory ReservationService
    participant ID as IdempotencyStore Redis
    participant SG as StockGate Redis
    participant DB as MySQL
    participant K as Kafka

    C->>GW: POST buy (JWT, Idempotency-Key)
    GW->>GW: validate JWT and rate limit
    GW->>CH: forward request
    CH->>CH: validate input, generate order_id
    CH->>IV: reserve (productId, customerId, key)
    IV->>ID: begin(key, requestHash)
    alt key already completed
        ID-->>IV: stored result
        IV-->>CH: same reservation as before
    else key in progress
        ID-->>IV: IN_PROGRESS
        IV-->>CH: 202 retry shortly
    else new key
        IV->>SG: tryAcquire(productId, 1)
        alt stock is zero
            SG-->>IV: false
            IV->>ID: complete(key, SOLD_OUT)
            IV-->>CH: 409 SOLD_OUT
        else acquired
            SG-->>IV: true
            IV->>DB: BEGIN transaction
            IV->>DB: insert reservation RESERVED
            IV->>DB: conditional update available to reserved
            IV->>DB: insert outbox ReservationCreated
            alt update affected 1 row
                IV->>DB: COMMIT
                IV->>ID: complete(key, reservation)
                IV-->>CH: reservation id, expiresAt
            else 0 rows or DB error
                IV->>DB: ROLLBACK
                IV->>SG: giveBack(productId, 1)
                IV->>ID: complete(key, FAILED)
                IV-->>CH: 409 or 503
            end
        end
    end
    CH-->>GW: response
    GW-->>C: reservation or error
    DB-)K: outbox publisher sends ReservationCreated
```

### 3.2 Payment

```mermaid
sequenceDiagram
    autonumber
    participant C as Customer
    participant CH as Checkout
    participant PS as PaymentService
    participant GD as IdempotencyGuard
    participant DB as Payment DB
    participant RG as ResilientPaymentGateway
    participant EX as External Gateway
    participant K as Kafka

    C->>CH: pay (Idempotency-Key)
    CH->>PS: initiate(orderId, reservationId, amount, key)
    PS->>GD: checkOrRegister(key, requestHash)
    alt duplicate key
        GD-->>PS: existing payment
        PS-->>CH: same payment status
    else new key
        PS->>DB: insert payment INITIATED (unique key)
        PS->>RG: charge(paymentId, amount)
        Note over RG,EX: timeout 3s, retry 3 times with same paymentId, circuit breaker
        RG->>EX: charge
        alt success
            EX-->>RG: success
            RG-->>PS: SUCCESS
            PS->>DB: status SUCCEEDED + outbox PaymentSucceeded
        else declined
            EX-->>RG: declined
            RG-->>PS: DECLINED
            PS->>DB: status FAILED + outbox PaymentFailed
        else timeout after retries
            RG-->>PS: UNKNOWN
            PS->>DB: status TIMEOUT
            Note over PS: PaymentReconciler and webhook resolve later
        else circuit open
            RG-->>PS: CIRCUIT_OPEN
            PS->>DB: mark attempt not started
            PS-->>CH: 503 Retry-After
        end
        PS-->>CH: payment status
    end
    DB-)K: outbox publisher sends PaymentSucceeded or PaymentFailed
    CH-->>C: success, failed or being verified
```

### 3.3 Order creation, confirmation and recovery

```mermaid
sequenceDiagram
    autonumber
    participant CH as Checkout Saga
    participant K as Kafka
    participant OS as OrderCommandConsumer
    participant SV as OrderService
    participant DB as Order DB
    participant IV as Inventory
    participant NT as Notification
    participant WD as SagaWatchdog

    CH-)K: CreateOrder (key order_id)
    K-)OS: CreateOrder
    OS->>SV: create(cmd)
    SV->>DB: insert order CREATED if absent + history + outbox
    CH-)K: PaymentInitiated
    K-)OS: PaymentInitiated
    OS->>SV: markPaymentPending
    SV->>DB: order PAYMENT_PENDING + history
    Note over CH,K: PaymentSucceeded received by Checkout
    CH-)K: ConfirmOrder (orderId, paymentId)
    alt Order Service available
        K-)OS: ConfirmOrder
        OS->>SV: confirm(orderId, paymentId)
        SV->>DB: order CONFIRMED + history + outbox OrderConfirmed
        SV-)K: OrderConfirmed
        K-)IV: OrderConfirmed then reservation SOLD
        K-)NT: send confirmation
    else Order Service down 30 s
        K--xOS: delivery fails
        Note over K,OS: message stays in Kafka, consumer retries with backoff
        WD->>CH: saga PAID without OrderConfirmed after 60 s
        CH-)K: resend ConfirmOrder (same ids)
        OS->>SV: confirm (idempotent, creates order if absent)
        SV->>DB: order CONFIRMED
        SV-)K: OrderConfirmed
    else message keeps failing 3 times
        K-)K: move to dead-letter topic
        Note over K: alert, operator replay, or compensation refund and release
    end
```

---

## 4. State Diagrams

### 4.1 Reservation lifecycle

```mermaid
stateDiagram-v2
    [*] --> RESERVED: Buy Now accepted
    RESERVED --> PAYMENT_PENDING: payment initiated
    RESERVED --> RELEASED: TTL expired or cancelled
    PAYMENT_PENDING --> CONFIRMED: PaymentSucceeded
    PAYMENT_PENDING --> RELEASED: PaymentFailed or grace over
    CONFIRMED --> SOLD: OrderConfirmed
    CONFIRMED --> RELEASED: compensation
    SOLD --> [*]
    RELEASED --> [*]
```

### 4.2 Order lifecycle

```mermaid
stateDiagram-v2
    [*] --> CREATED
    CREATED --> PAYMENT_PENDING: payment started
    CREATED --> CANCELLED: expired or cancelled
    PAYMENT_PENDING --> CONFIRMED: PaymentSucceeded
    PAYMENT_PENDING --> CANCELLED: payment failed
    CONFIRMED --> PROCESSING
    CONFIRMED --> CANCELLED: cancel and refund
    PROCESSING --> SHIPPED
    PROCESSING --> CANCELLED: cancel and refund
    SHIPPED --> OUT_FOR_DELIVERY
    OUT_FOR_DELIVERY --> DELIVERED
    DELIVERED --> [*]
    CANCELLED --> [*]
```

---

## 5. Where Validation, Idempotency and Error Handling Belong

### 5.1 Validation (each layer checks only what it owns)

| Layer | Validates | Example |
|---|---|---|
| **API Gateway** | Authentication, rate limit, request size, required headers | Missing JWT → 401; more than 5 buys/min per user → 429; missing `Idempotency-Key` → 400 |
| **Controller (DTO)** | Shape and format of input | Quantity must be 1 to the per-user limit; IDs well-formed |
| **Application service** | Business preconditions | Sale is active, customer has no active reservation, amount equals the priced total |
| **Domain entity / State** | Legal state transitions, invariants | `ship()` on a CREATED order is rejected; releasing a SOLD reservation is rejected |
| **Database** | Last-line integrity | Unique idempotency key, non-negative quantities, foreign keys |

Principle: **never trust the previous layer for integrity**. The DB constraints stay even though services validate.

### 5.2 Idempotency (per operation)

| Operation | Where | Key | Mechanism |
|---|---|---|---|
| Buy Now / reserve | `ReservationService` via `IdempotencyStore` | `Idempotency-Key` header | Redis set-if-absent with stored result, plus DB unique key |
| One active reservation per customer | Reservation repository | customer + product | DB unique rule on active rows |
| Initiate payment | `PaymentIdempotencyGuard` | Idempotency-Key (derived from reservation id) | Unique key in Payment DB, request-hash check |
| Gateway charge | `ResilientPaymentGateway` / adapter | `payment_id` as merchant reference | Gateway-side idempotency, safe retries |
| Refund | `PaymentService` | `payment_id` + refund reason | Unique refund reference |
| Kafka consumers (confirm, release, order commands) | Consumer + service | `event_id` / `payment_id` / `order_id` | Processed-events check and monotonic state transitions |
| Order creation / confirm | `OrderService` | `order_id` (create if absent) | Primary key, state machine ignores repeats |

### 5.3 Error handling

| Error type | Where handled | Response |
|---|---|---|
| Validation error | Controller advice | 400 with error code |
| Unauthorized / forbidden | Gateway | 401 / 403 |
| Rate limited | Gateway | 429 with `Retry-After` |
| Sold out | `ReservationService` | 409 `SOLD_OUT` (or `TEMPORARILY_UNAVAILABLE`) |
| Idempotency conflict (same key, different payload) | Idempotency layer | 422 / 409 |
| Duplicate in progress | Idempotency layer | 202 with retry hint |
| Invalid state transition | Domain `State` objects | Domain exception → 409 `INVALID_STATE` |
| Payment declined | `PaymentService` | Business outcome FAILED (not an exception), event published |
| Gateway timeout / 5xx | `ResilientPaymentGateway` | Retry, then TIMEOUT state, reconcile |
| Circuit open | `ResilientPaymentGateway` | Fail fast, 503 `Retry-After`, reservation kept |
| Database / Redis down | Service boundary | 503; compensate Redis counter; client retries with same key |
| Poison message | Kafka consumer error handler | 3 retries then dead-letter topic and alert |

**Exception model:** a small hierarchy: `BusinessRuleException` (sold out, invalid state, conflict) maps to 4xx and is never retried; `TransientException` (timeout, connection) maps to 5xx and is retried with the same idempotency key; `FatalException` (programming or data corruption) alerts and goes to the dead-letter topic. A single global exception handler per service converts these to the standard error body (code, message, trace id).

---

## 6. Package Layout per Service (Spring Boot guidance, no code)

```
com.salestorm.inventory
 ├─ api            controllers, request/response DTOs, exception advice
 ├─ application    ReservationService, schedulers, event consumers
 ├─ domain         Reservation, ReservationState + concrete states, domain events, repository interfaces, StockGate interface
 ├─ infrastructure JPA repositories, RedisStockGate, RedisIdempotencyStore, outbox publisher, Kafka config
 └─ config
```

Rule: **`domain` depends on nothing; `application` depends on `domain`; `infrastructure` implements `domain` interfaces.** This is Dependency Inversion in practice and lets tests replace Redis or the gateway with fakes. Payment and Order follow the same layout.

---

## 7. How the LLD Answers the Jury's Questions

| Question | Where in this LLD |
|---|---|
| Why is this the correct service boundary? | Section 1: one owner per invariant (stock, money, lifecycle) |
| Where exactly is inventory consistency guaranteed? | `InventoryRepository` conditional update inside the reservation transaction (sequence 3.1) |
| What if two requests hit Inventory at once? | `StockGate` serialises; DB conditional update arbitrates |
| Why sync here, async there? | Reserve and initiate payment are sync (customer waits); order, inventory confirmation and notifications are commands/events |
| Payment success but Order fails? | Sequence 3.3: Kafka durability, idempotent consumer, watchdog, reconciler, DLQ, compensation |
| How easy is adding a payment provider? | New `PaymentGateway` adapter plus factory registration; `PaymentService` unchanged |
