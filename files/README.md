# SALESTORM – High-Scale Flash-Sale Platform (Design Blueprint)

**SYSCRAFTERS 2026 – Design-First, AI-Assisted System Design Hackathon**

> 100 units, 10,000 simultaneous "Buy Now" requests: no overselling, no duplicate payments, every paid order ends CONFIRMED or refunded.

## One-paragraph design

Traffic is absorbed at the CDN/WAF and API Gateway. **Redis** is the fast atomic gatekeeper that admits only about 100 requests; **MySQL** is the final truth, protected by a conditional update (`available > 0`) and non-negative constraints in the same transaction as the reservation. Services own their data and communicate with REST (where an answer is needed immediately) and **Kafka events** (outbox + idempotent consumers). A **Checkout saga** coordinates reservation, payment and order with compensation; **reconcilers** and a **watchdog** repair anything that fails. Payment uses idempotency keys, a 3 s timeout, same-`payment_id` retries and a circuit breaker; an unknown outcome (`TIMEOUT`) is reconciled, never guessed.

## Folder structure and reading order

| # | Folder | Contents |
|---|---|---|
| 1 | `01_Requirements/` | Requirements and assumptions (Stage 1) |
| 2 | `02_HLD/` | System context, container, component, deployment diagrams (Stage 2) |
| 3 | `03_LLD/` | Class, sequence and state diagrams for Inventory, Payment, Order (Stage 5), plus Stage 3 concurrency & inventory and Stage 4 payment & order designs |
| 4 | `04_Database/` | `database_design.md`: ER diagram, keys, constraints, indexes, transaction boundaries |
| 5 | `05_API/` | API specification and event design (Stage 7) |
| 6 | `06_SOLID/` | `solid_mapping.md` (Stage 6A) |
| 7 | `07_Design_Patterns/` | `design_patterns.md` (Stage 6B) |
| 8 | `08_Scalability_Reliability/` | `scalability_reliability.md`: load model, bottlenecks, 50x, failure recovery |
| 9 | `09_Security_Observability/` | `security_observability.md`: authN/Z, rate limits, audit, metrics, logs, tracing, alerts |
| 10 | `10_ADR/` | Architecture decision record (Stage 8) |
| 11 | `11_AI_Assisted_Validation/` | `ai_usage_note.md` (no simulation submitted) |
| 12 | `12_Presentation/` | `SALESTORM_Pitch.pptx` (5-minute pitch) |

> Place the existing files from earlier stages in the matching folders. Some earlier documents refer to `07_Design_Patterns/design_patterns.md`; keep that path.

## Deliverables checklist (brief section 12)

| # | Deliverable | Location | Status |
|---|---|---|---|
| 1 | Requirements & Assumptions | 01 | Done (Stage 1) |
| 2-6 | Context, HLD, Container, Component, Deployment diagrams | 02 | Done (Stage 2) |
| 7 | Database / ER diagram | 04 | **Done** |
| 8-12 | Class, Purchase, Payment, Order sequence, State diagrams | 03 | Done (Stage 5, 3, 4) |
| 13 | SOLID mapping | 06 | Done |
| 14 | Design pattern mapping | 07 | Done |
| 15 | API specification | 05 | Done (Stage 7) |
| 16 | Scalability & Reliability | 08 | **Done** |
| 17 | Security & Observability | 09 | **Done** |
| 18 | ADR | 10 | Done (Stage 8) |
| 19 | AI prototype / simulation | 11 | Optional, not submitted |
| 20 | AI usage note | 11 | **Done** |
| 21 | Final presentation | 12 | **Done** |

## Key guarantees (strict) vs targets

| Strict guarantees | Targets |
|---|---|
| Sales never exceed 100; inventory never negative | Sold-out response under 50 ms |
| One idempotency key = one reservation; one reservation = one active payment | Order confirmed under 5 s (under 60 s in an outage) |
| Valid state transitions only, with audit history | Expired holds released within 30 s |
| Every successful payment ends in a confirmed order or a refund | Redis counter equals MySQL available (eventual) |

## Team and AI use

Roles: System Architect, LLD & Design Engineer, Data & API Engineer, Reliability Engineer. All members can explain every module. AI (Claude) was used only for documentation drafting; see `11_AI_Assisted_Validation/ai_usage_note.md`.
