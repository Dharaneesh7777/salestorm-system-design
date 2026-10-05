# SALESTORM – Stage 7A: REST API Specification

Base path: `/api/v1` (URL versioning). Format: `application/json`. All traffic over HTTPS (TLS 1.2+).
**Public APIs** go through CDN/WAF → Load Balancer → API Gateway. **Internal APIs** (`/internal/...`) are never exposed through the gateway.

---

## 1. Common Conventions

### 1.1 Authentication and authorization

| Caller | Mechanism | Details |
|---|---|---|
| Customer | `Authorization: Bearer <JWT>` | Signed by the Identity Provider (RS256). Gateway validates signature, expiry (15 min) and audience using the provider's public keys. Claims: `sub` (customerId), `roles`, `exp`. |
| Admin | Same JWT with role `ADMIN` | Admin endpoints require the role; actions are audit-logged. |
| Service to service | mTLS or short-lived signed service token | `/internal/...` only reachable inside the private network. The calling service identity is checked. |
| Payment gateway callback | HMAC signature header + timestamp + IP allow-list | No customer JWT; replayed or old timestamps rejected. |

**Authorization rules (checked in the service, not only at the gateway):**
- A customer can read or cancel **only their own** orders, reservations and payments (`customerId` from the token must match the owner), otherwise `404` (do not reveal existence).
- The customer id is **always taken from the token**, never from the request body.

### 1.2 Standard headers

| Header | Direction | Purpose |
|---|---|---|
| `Authorization` | Request | Bearer JWT |
| `Idempotency-Key` | Request | Client-generated UUID, **required** on state-changing customer calls (see section 6) |
| `traceparent` | Request | W3C trace context; gateway creates one if absent |
| `X-Trace-Id` | Response | Same trace id, for support and log search |
| `Idempotent-Replayed: true` | Response | Set when a stored result is returned for a repeated key |
| `Retry-After` | Response | Seconds to wait on 202, 429, 503 |
| `X-RateLimit-Limit`, `X-RateLimit-Remaining` | Response | Rate-limit state |

### 1.3 Rate limits (enforced at the gateway with Redis)

| Endpoint group | Limit |
|---|---|
| `POST /checkout/buy` | 5 per minute per user, plus per-IP and global flood protection |
| `POST /checkout/{orderId}/pay` | 10 per minute per user |
| Read endpoints | 120 per minute per user |
| Admin | 60 per minute per admin |

### 1.4 Standard error body (all services)

```json
{
  "code": "SOLD_OUT",
  "message": "Product is sold out",
  "details": [],
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "timestamp": "2026-10-05T10:15:30Z"
}
```
`code` is machine-readable and stable; `message` is human-readable; `details` lists field errors for validation failures.

### 1.5 Pagination (list endpoints)
`?page=0&size=20` (max size 50). Response includes `items`, `page`, `size`, `totalItems`.

---

## 2. Public Customer APIs

### 2.1 Product and Sale (cacheable reads)

| Method | Endpoint | Auth | Success | Notes |
|---|---|---|---|---|
| GET | `/sales/{saleId}` | JWT | 200 | Sale window, status (UPCOMING, ACTIVE, PAUSED, ENDED), pricing type. CDN/Redis cached (short TTL). |
| GET | `/sales/{saleId}/products` | JWT | 200 | Products in the sale, paginated |
| GET | `/products/{productId}` | JWT | 200 | Product details and display price; stock shown as `IN_STOCK`, `LOW_STOCK`, `SOLD_OUT` (approximate, cached) |

Errors: `404 NOT_FOUND`, `401 UNAUTHORIZED`, `429 RATE_LIMITED`.

### 2.2 Cart

| Method | Endpoint | Request | Success |
|---|---|---|---|
| GET | `/cart` | none | 200 cart |
| POST | `/cart/items` | `{productId, quantity}` | 201 cart item |
| PATCH | `/cart/items/{itemId}` | `{quantity}` | 200 |
| DELETE | `/cart/items/{itemId}` | none | 204 |

Cart does **not** reserve stock; only Buy Now or checkout does. Adding a duplicate item updates the quantity (naturally idempotent). Errors: `400 VALIDATION_ERROR`, `404`, `409 SALE_NOT_ACTIVE`.

### 2.3 Buy Now (reserve inventory)

**`POST /checkout/buy`** – Synchronous. Creates the reservation and starts the checkout saga.

Headers: `Authorization`, `Idempotency-Key` (required)

Request:
```json
{
  "productId": "P-1001",
  "quantity": 1,
  "couponCode": "FLASH10"
}
```
Validation: `quantity` 1 to per-user limit (default 1), sale ACTIVE, `couponCode` optional and valid.

**Success `201 Created`**
```json
{
  "orderId": "ORD-8f3a...",
  "reservationId": "RSV-41c2...",
  "status": "RESERVED",
  "expiresAt": "2026-10-05T10:20:30Z",
  "price": { "currency": "INR", "subtotal": 4999.00, "discount": 500.00, "total": 4499.00 }
}
```

**Responses**

| Status | Code | Meaning | Client action |
|---|---|---|---|
| 201 | n/a | Reserved | Go to payment before `expiresAt` |
| 200 | n/a (header `Idempotent-Replayed: true`) | Duplicate request, original result returned | Treat as success |
| 202 | `REQUEST_IN_PROGRESS` | Same key still processing | Retry after `Retry-After` with same key |
| 400 | `VALIDATION_ERROR` / `IDEMPOTENCY_KEY_REQUIRED` | Bad input or missing key | Fix request |
| 401 / 403 | `UNAUTHORIZED` / `FORBIDDEN` | Auth failure | Re-login |
| 409 | `SOLD_OUT` | No units and no pending reservations | Show sold out |
| 409 | `TEMPORARILY_UNAVAILABLE` | Units all reserved but unpaid; some may return | Show "check back" |
| 409 | `ACTIVE_RESERVATION_EXISTS` | Customer already holds a reservation | Return the existing one |
| 409 | `SALE_NOT_ACTIVE` | Sale not open or paused | Show sale status |
| 422 | `IDEMPOTENCY_KEY_REUSED` | Same key, different payload | Use a new key |
| 429 | `RATE_LIMITED` | Too many requests | Wait |
| 503 | `SERVICE_UNAVAILABLE` | Redis/DB problem, fail closed | Retry with same key |

### 2.4 Pay

**`POST /checkout/{orderId}/pay`** – Synchronous with bounded wait (about 3 s gateway timeout).

Headers: `Authorization`, `Idempotency-Key` (required)

Request:
```json
{
  "paymentMethod": "CARD",
  "paymentToken": "tok_9f2c..."
}
```
Only a **tokenised** payment reference is accepted. Raw card numbers, CVV or other sensitive data are never accepted, logged or stored (PCI scope reduction).

Validation: order belongs to the caller; reservation active; amount derived by the server from the stored price (client cannot send an amount).

**Responses**

| Status | Code / body status | Meaning |
|---|---|---|
| 200 | `status: SUCCEEDED` | Payment confirmed; order confirmation follows asynchronously |
| 200 | `status: FAILED`, `reason` | Declined; reservation will be released |
| 202 | `status: PROCESSING` or `TIMEOUT` ("being verified") | Outcome pending; poll status; no new payment needed |
| 200 + `Idempotent-Replayed` | any | Duplicate request, existing payment returned |
| 404 | `ORDER_NOT_FOUND` | Not found or not owned |
| 409 | `RESERVATION_EXPIRED` | Reservation already released |
| 409 | `INVALID_STATE` | Order not payable in its current state |
| 422 | `IDEMPOTENCY_KEY_REUSED` | Same key, different payload |
| 503 | `PAYMENT_UNAVAILABLE` + `Retry-After` | Circuit open; reservation kept; retry within TTL |

Success body:
```json
{
  "paymentId": "PAY-77ab...",
  "orderId": "ORD-8f3a...",
  "status": "SUCCEEDED",
  "message": "Payment received, confirming your order"
}
```

### 2.5 Checkout status (polling)

**`GET /checkout/{orderId}/status`** – returns the combined progress for the customer UI.
```json
{
  "orderId": "ORD-8f3a...",
  "orderStatus": "PAYMENT_PENDING",
  "reservationStatus": "PAYMENT_PENDING",
  "paymentStatus": "SUCCEEDED",
  "stage": "PAID_CONFIRMING_ORDER",
  "message": "Payment received, confirming your order",
  "expiresAt": null
}
```
Reads from the Checkout saga record, so it still answers when Order Service is down. Errors: `404`.

### 2.6 Orders

| Method | Endpoint | Request | Success | Notes |
|---|---|---|---|---|
| GET | `/orders` | `?page&size` | 200 list | Caller's orders only |
| GET | `/orders/{orderId}` | none | 200 | Includes state and status history |
| GET | `/orders/{orderId}/tracking` | none | 200 | Shipment and delivery tracking |
| POST | `/orders/{orderId}/cancel` | `{reason}` + `Idempotency-Key` | 200 | Allowed in CREATED, PAYMENT_PENDING, CONFIRMED, PROCESSING |

Cancel errors: `409 CANCEL_NOT_ALLOWED` (already SHIPPED, DELIVERED or CANCELLED), `404`, `422 IDEMPOTENCY_KEY_REUSED`. A repeated cancel returns the already-cancelled order (idempotent). Refund starts asynchronously (`OrderCancelled` event).

Order response:
```json
{
  "orderId": "ORD-8f3a...",
  "status": "CONFIRMED",
  "items": [{ "productId": "P-1001", "quantity": 1, "unitPrice": 4499.00 }],
  "total": 4499.00,
  "paymentId": "PAY-77ab...",
  "history": [
    { "from": "CREATED", "to": "PAYMENT_PENDING", "at": "2026-10-05T10:16:00Z" },
    { "from": "PAYMENT_PENDING", "to": "CONFIRMED", "at": "2026-10-05T10:16:03Z" }
  ]
}
```

---

## 3. Internal Service APIs (not exposed through the gateway)

### 3.1 Inventory-Reservation Service

| Method | Endpoint | Purpose | Idempotency | Success | Errors |
|---|---|---|---|---|---|
| POST | `/internal/v1/reservations` | Reserve units (called by Checkout) | `Idempotency-Key` header | 201 / 200 replay | 409 SOLD_OUT, 409 TEMPORARILY_UNAVAILABLE, 409 ACTIVE_RESERVATION_EXISTS, 503 |
| GET | `/internal/v1/reservations/{id}` | Read reservation | n/a | 200 | 404 |
| POST | `/internal/v1/reservations/{id}/release` | Manual or compensation release (admin tooling) | Naturally idempotent (already RELEASED returns 200) | 200 | 409 INVALID_STATE (SOLD) |

Confirmation, SOLD marking and expiry are event/scheduler driven, not public APIs.

### 3.2 Payment Service

| Method | Endpoint | Purpose | Idempotency | Success | Errors |
|---|---|---|---|---|---|
| POST | `/internal/v1/payments` | Initiate payment (called by Checkout) | `Idempotency-Key` + unique per reservation | 200 / 202 / replay | 409 RESERVATION_EXPIRED, 422 IDEMPOTENCY_KEY_REUSED, 503 PAYMENT_UNAVAILABLE |
| GET | `/internal/v1/payments/{id}` | Payment status | n/a | 200 | 404 |
| POST | `/internal/v1/payments/{id}/refunds` | Start refund | Unique refund reference per payment | 202 | 409 NOT_REFUNDABLE |

Public webhook (verified by signature):

| Method | Endpoint | Purpose | Handling |
|---|---|---|---|
| POST | `/webhooks/payment-gateway` | Gateway result callback | Verify HMAC and timestamp; look up by `paymentId`; apply result **idempotently**; respond `200` quickly (even for already-processed events) so the gateway stops retrying; `401` for a bad signature |

### 3.3 Order Service

Order creation, payment-pending marking, confirmation and cancellation by the saga are **commands over Kafka** (see `event_design.md`), not REST, so an Order Service outage does not block the purchase path. Customer read and cancel endpoints are in section 2.6.

### 3.4 Checkout Service
Exposes the public endpoints in sections 2.3 to 2.5. Calls Inventory and Payment synchronously through the internal APIs above with timeouts, retries (same idempotency key) and circuit breakers.

---

## 4. Admin APIs (role ADMIN, audited)

| Method | Endpoint | Purpose | Notes |
|---|---|---|---|
| POST | `/admin/sales` | Create sale (window, pricing type, per-user limit) | 201 |
| PUT | `/admin/sales/{saleId}` | Update sale configuration | 200 |
| POST | `/admin/sales/{saleId}/pause` | Pause or resume a sale (kill switch) | Idempotent |
| POST | `/admin/inventory/{productId}/load` | Load or add stock | Writes MySQL first, then sets the Redis counter; idempotent with `Idempotency-Key` |
| POST | `/admin/inventory/{productId}/reconcile` | Force Redis ↔ MySQL reconciliation | 202 |
| GET | `/admin/audit` | Audit log search (who, what, when) | Paginated |
| GET | `/admin/dlq/{topic}` | List dead-lettered messages | |
| POST | `/admin/dlq/{topic}/{messageId}/replay` | Replay a dead-lettered message | Safe because consumers are idempotent |

---

## 5. Error Code Catalogue

| Code | HTTP | Retryable by client | Meaning |
|---|---|---|---|
| `VALIDATION_ERROR` | 400 | No | Invalid input; `details` lists fields |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | No | Header missing |
| `UNAUTHORIZED` | 401 | No (re-login) | Missing, invalid or expired token |
| `FORBIDDEN` | 403 | No | Role not allowed |
| `NOT_FOUND`, `ORDER_NOT_FOUND` | 404 | No | Missing or not owned |
| `SOLD_OUT` | 409 | No | Nothing left and nothing pending |
| `TEMPORARILY_UNAVAILABLE` | 409 | Later | Units reserved by others, may return |
| `ACTIVE_RESERVATION_EXISTS` | 409 | No | One active reservation rule |
| `SALE_NOT_ACTIVE` | 409 | Later | Outside sale window or paused |
| `RESERVATION_EXPIRED` | 409 | No (buy again) | Hold already released |
| `INVALID_STATE` / `CANCEL_NOT_ALLOWED` | 409 | No | Illegal state transition |
| `REQUEST_IN_PROGRESS` | 202 | Yes, same key | Original still running |
| `IDEMPOTENCY_KEY_REUSED` | 422 | No | Same key, different body |
| `RATE_LIMITED` | 429 | Yes, after `Retry-After` | Throttled |
| `PAYMENT_UNAVAILABLE` | 503 | Yes, same key | Circuit open |
| `SERVICE_UNAVAILABLE` | 503 | Yes, same key | Dependency down (fail closed) |
| `INTERNAL_ERROR` | 500 | Yes, same key | Unexpected; traceId for support |

**Payment decline is a business outcome**, returned as `200` with `status: FAILED`, not an HTTP error.

**Error handling placement** (ties to Stage 5): validation errors from the controller layer, business-rule errors from the service/domain layer, transient dependency errors mapped to 503, and one global exception handler per service producing the standard body above. Internal details and stack traces never reach the client.

---

## 6. Idempotency Design

### 6.1 Which operations need it

| Operation | Why repeated calls are dangerous | Key | Enforcement |
|---|---|---|---|
| `POST /checkout/buy` | Double click or retry could reserve two units | Client `Idempotency-Key` | Redis set-if-absent with stored result + unique key in reservation table + one-active-reservation rule |
| `POST /checkout/{orderId}/pay` | Duplicate charge | Client `Idempotency-Key` | Payment DB unique key, one active payment per reservation, `paymentId` sent to gateway as its reference |
| `POST /orders/{id}/cancel` | Double refund | Client `Idempotency-Key` | State machine (already CANCELLED returns same result), unique refund reference |
| `POST /internal/v1/reservations`, `/payments`, `/refunds` | Retry from Checkout after timeout | Forwarded key | Same mechanisms as above |
| `POST /admin/inventory/{id}/load` | Stock loaded twice | `Idempotency-Key` | Stored result |
| Kafka commands and events | At-least-once delivery | `eventId` | Inbox table (see `event_design.md`) |
| GET, PUT (full replace), DELETE | Naturally idempotent | none | n/a |

### 6.2 Behaviour rules

| Case | Result |
|---|---|
| Same key, same request, already completed | Original response again, header `Idempotent-Replayed: true` |
| Same key, same request, still processing | `202 REQUEST_IN_PROGRESS` with `Retry-After`; **no second operation starts** |
| Same key, different request body (compared by hash) | `422 IDEMPOTENCY_KEY_REUSED` |
| Key missing on a protected endpoint | `400 IDEMPOTENCY_KEY_REQUIRED` |
| Failed result | Stored too; a retry with the same key returns the same failure, so the client must use a new key for a fresh attempt |
| Retention | Redis 24 hours; business-level unique constraints (reservation, payment) persist permanently |

---

## 7. Sync vs Async Summary for APIs

| API | Mode | Reason |
|---|---|---|
| Buy Now | Sync | Customer needs an immediate yes or no |
| Pay | Sync with bounded wait, falls back to 202 | Gateway usually answers within seconds; if not, the status endpoint takes over |
| Order confirmation | Async (command and event) | Must survive Order Service outage |
| Cancel and refund | Cancel sync, refund async | Customer sees cancellation immediately; money movement is eventual |
| Notifications, shipment | Async | Never block the purchase |

---

## 8. Sample Walkthrough (happy path)

1. `POST /checkout/buy` with key K1 → `201` reservation, 5-minute expiry.
2. `POST /checkout/{orderId}/pay` with key K2 → `200 SUCCEEDED`.
3. `GET /checkout/{orderId}/status` → `PAID_CONFIRMING_ORDER`, then `ORDER_CONFIRMED`.
4. `GET /orders/{orderId}` → `CONFIRMED`; later `SHIPPED`, `OUT_FOR_DELIVERY`, `DELIVERED` via tracking events.
