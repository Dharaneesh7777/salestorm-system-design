# SALESTORM – Stage 6A: SOLID Principles Mapped to the Design

All class names below are the ones in the Stage 5 class diagrams. Each principle is shown as: **what the rule says, where we apply it, what would go wrong without it.**

---

## 1. S – Single Responsibility Principle

*A class should have one reason to change.*

| Class / Component | Its single responsibility | Reason it changes | Deliberately NOT its job |
|---|---|---|---|
| `ReservationService` | Coordinate gate, DB and outbox for stock changes | Reservation business rule changes | Redis details, SQL, HTTP |
| `RedisStockGate` | Atomic stock counter in Redis | Redis technique changes | Business rules |
| `RedisIdempotencyStore` | Remember request results by key | Idempotency storage changes | Stock or payment logic |
| `Reservation` + `ReservationState` classes | Own legal reservation transitions | Lifecycle rules change | Persistence |
| `PaymentService` | Run the payment workflow | Payment flow changes | Talking to a specific provider |
| `MockGatewayAdapter`, `RazorpayAdapter` | Translate to one provider's API | That provider's API changes | Retry, circuit breaking |
| `ResilientPaymentGateway` | Timeout, retry, circuit breaker | Resilience policy changes | Provider mapping, business rules |
| `PaymentReconciler` | Resolve TIMEOUT payments | Reconciliation schedule/policy | Charging new payments |
| `OrderService` | Order use-cases and transactions | Order workflow changes | Transition rules, notification sending |
| `Order` + `OrderState` classes | Legal order transitions | Order lifecycle changes | Persistence, messaging |
| `OrderEventPublisher` / `OrderListener`s | Announce and react to order events | New reaction needed | Order rules |
| `CheckoutFacade` | Entry point for purchase steps | Checkout steps change | Stock, payment, order internals |
| `CheckoutSaga` | Track saga progress and compensation | Saga rules change | Business data ownership |
| `PricingStrategy` implementations | Calculate one kind of price | That pricing rule changes | Reservation or payment |
| `ExpiryScheduler` | Trigger expiry on a schedule | Schedule changes | The release logic itself (calls `ReservationService`) |

**Brief's explicit demonstration:** payment processing (`PaymentService`), order management (`OrderService`) and notification (`NotificationListener`) are three separate classes in three separate services, so a change to one never forces a change to another.

---

## 2. O – Open/Closed Principle

*Open for extension, closed for modification.*

| Extension point | Abstraction | Add this to extend | Existing code modified |
|---|---|---|---|
| New payment provider | `PaymentGateway` | New adapter class | None in `PaymentService` (only registration/config) |
| New pricing rule (deal, coupon, early-bird) | `PricingStrategy` | New strategy class | None in `CheckoutFacade` |
| New delivery partner | `DeliveryPartner` | New adapter class | None in `ShipmentService` |
| New order state (for example RETURNED) | `OrderState` | New state class plus allowed transitions | `Order` unchanged; only neighbouring state classes get the new transition |
| New order reaction (email, loyalty points) | `OrderListener` | New listener | None in `OrderService` |
| New notification channel | `NotificationChannel` | New channel class | None in notification logic |
| New concurrency gate (different cache) | `StockGate` | New implementation | None in `ReservationService` |

Full walkthroughs are in `07_Design_Patterns/design_patterns.md`, section 4.

**Without OCP:** adding a provider would mean editing `PaymentService` with another `if provider == ...` branch, risking the payment code that must never break.

---

## 3. L – Liskov Substitution Principle

*Any implementation must be usable wherever its abstraction is expected, without surprising the caller.*

### Contract for every `PaymentGateway` implementation
| Rule | Why callers depend on it |
|---|---|
| `charge` is idempotent for the same `paymentId` | Retries must never double charge |
| A decline returns a `DECLINED` result and does **not** throw | Business outcome is not an error |
| A timeout or unknown outcome returns `UNKNOWN` (never `FAILED`) | Prevents releasing stock/money on a guess |
| `queryStatus(paymentId)` returns the real state or `NOT_FOUND` | Reconciler relies on it |
| Amounts and currency are never silently changed | Money correctness |

`MockGatewayAdapter`, `RazorpayAdapter`, `StripeAdapter` and the decorator `ResilientPaymentGateway` all honour this contract, so `PaymentService` cannot tell which one it holds.

### Other substitution points
| Abstraction | Substitutes | Rule |
|---|---|---|
| `ReservationState` | `ReservedState`, `PaymentPendingState`, `ConfirmedState`, `SoldState`, `ReleasedState` | Illegal actions raise the same `InvalidStateTransition` error, never silently succeed |
| `OrderState` | All eight order states | Same rule |
| `PricingStrategy` | `FlatPricing`, `DealPricing`, `CouponPricing` | Never returns a negative price, always same currency |
| `StockGate` | `RedisStockGate`, in-memory fake for tests | Never lets stock go below zero |
| `DeliveryPartner` | Each courier adapter | Same shipment result model and error semantics |

**How we enforce LSP:** one **shared contract test suite** per interface, run against every implementation (including the mock). A new provider that fails the suite cannot be registered.

**Without LSP:** a new adapter that throws on decline would make `PaymentService` mark a normal card decline as a system error.

---

## 4. I – Interface Segregation Principle

*Clients should not depend on methods they do not use.*

| Large concern | Split into focused interfaces | Who depends on which |
|---|---|---|
| Payment provider | `PaymentCharger` (charge), `RefundProcessor` (refund), `PaymentStatusProvider` (queryStatus). `PaymentGateway` simply combines the three for adapters. | `PaymentService` charge flow uses `PaymentCharger`; refund flow uses `RefundProcessor`; `PaymentReconciler` and the webhook handler need only `PaymentStatusProvider` |
| Inventory persistence | `InventoryRepository` (stock buckets) vs `ReservationRepository` (reservation rows) | `ExpiryScheduler` needs the reservation finder only through the service |
| Stock protection | `StockGate` (Redis counter) separate from `IdempotencyStore` (request results) | Different reasons to change, different clients |
| Order events | `OrderEventPublisher` (publish) vs `OrderListener` (react) | Publisher side and listener side evolve separately |
| Service clients | `InventoryClient`, `PaymentClient` separate in Checkout | Checkout sees only the calls it needs |
| Outbox | `OutboxWriter` (add) vs publisher job (read and send) | Domain code can only add events, never publish directly |

**Honest note on `OrderState`:** it has one method per transition, which is larger than ideal. We accept this because the states are a closed, small set and a single interface keeps `Order` simple. If the lifecycle grew, we would split it into role interfaces (for example `Cancellable`, `Shippable`) so that states only implement what applies.

**Without ISP:** the reconciler would depend on `charge` and `refund` it must never call, making accidental double charging easier.

---

## 5. D – Dependency Inversion Principle

*High-level policy depends on abstractions, not on concrete details.*

| High-level class | Depends on (abstraction) | Concrete detail hidden behind it |
|---|---|---|
| `ReservationService` | `StockGate`, `InventoryRepository`, `ReservationRepository`, `IdempotencyStore`, `OutboxWriter` | Redis Lua, JPA/MySQL, outbox table |
| `PaymentService` | `PaymentGatewayFactory` → `PaymentGateway` | Razorpay, Stripe, mock |
| `PaymentService` | `PaymentRepository`, `OutboxWriter` | MySQL, Kafka |
| `OrderService` | `OrderRepository`, `OrderEventPublisher` | MySQL, outbox/Kafka |
| `CheckoutFacade` | `InventoryClient`, `PaymentClient`, `PricingStrategy` | REST/Feign clients, concrete pricing |
| `ShipmentService` | `DeliveryPartner`, `DeliverySelectionStrategy` | Courier APIs |
| `NotificationService` | `NotificationChannel` | Email/SMS/push providers |

**Package rule that enforces it:** `domain` has no dependency on `infrastructure`; interfaces live in `domain`, implementations live in `infrastructure`, and Spring wires them at startup.

**Practical payoff:** unit tests replace Redis, MySQL and the payment gateway with fakes, so the concurrency and payment logic can be tested without Docker. The optional simulation uses the same fakes.

**Without DIP:** `ReservationService` would call Redis and SQL directly, making it impossible to test the oversell logic in isolation or to replace Redis later.

---

## 6. One-Page SOLID Summary

| Principle | Headline example | Benefit for SALESTORM |
|---|---|---|
| SRP | Payment, Order, Notification are separate classes and services | Failures and changes stay contained |
| OCP | New provider, pricing rule or courier = new class | Core payment and checkout code stays untouched and safe |
| LSP | Contract tests for every gateway adapter | Swapping providers cannot break payment correctness |
| ISP | `PaymentStatusProvider` for the reconciler | A component cannot call what it should never call |
| DIP | `ReservationService` depends on `StockGate`, not Redis | Testable concurrency logic, replaceable infrastructure |
