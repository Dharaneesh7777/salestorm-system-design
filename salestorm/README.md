# SaleStorm — Mission-Critical Flash Sale Engine & War Room

SaleStorm is an ultra-high-concurrency flash sale engine and saga orchestration system built with Spring Boot 3 (Java 17). It provides mathematical zero-oversell guarantees under massive concurrent spikes (10,000+ simultaneous requests) using a multi-layer defense architecture, transactional outbox pattern, and distributed saga compensation.

---

## 🚀 Quick Start

### 1. Start the Application
From the `salestorm` directory, launch the application:

```powershell
# In PowerShell (set JAVA_HOME if not already configured in your environment)
$env:JAVA_HOME = "C:\Program Files\Java\jdk-27"
.\mvnw.cmd spring-boot:run
```

```bash
# In Linux / macOS / WSL
export JAVA_HOME=/path/to/jdk-17
./mvnw spring-boot:run
```

### 2. Open the "SALESTORM War Room" Web Dashboard
Navigate in your browser to:
👉 **`http://localhost:8080/dashboard.html`**

*(Swagger UI is also available at `http://localhost:8080/swagger-ui/index.html` and H2 Web Console at `http://localhost:8080/h2-console`)*

---

## 🖥️ SALESTORM War Room Dashboard Overview

The **SALESTORM War Room** (`dashboard.html`) is a real-time command center providing live telemetry, automated failure injection, scenario verification, and interactive load testing.

### 1. Top Bar & Invariant Monitor
- **SSE Stream Status**: Live connection indicator connected to Server-Sent Events (`GET /api/v1/dashboard/stream`) pushing updates every 1 second with automatic reconnection.
- **Stock Invariant Badge**: Real-time validation of the fundamental conservation invariant:
  $$\text{Available} + \text{Reserved} + \text{Sold} == \text{Total Stock}$$
  Displays a green badge when verified, or a prominent red alert if any drift or invariant violation is detected.

### 2. KPI Cards & Computation
| KPI Card | Formula / Source | Description |
| :--- | :--- | :--- |
| **Available Stock** | `INVENTORY.available_quantity` | Quantity of units currently available in DB for instant checkout. |
| **Reserved Stock** | `INVENTORY.reserved_quantity` | Units currently held in active 300-second reservation leases. |
| **Sold Stock** | `INVENTORY.sold_quantity` | Units with completed payments and confirmed orders ($\le \text{Total Stock}$). |
| **StockGate Count** | `StockGate.getAvailable(1L)` | Atomic in-memory gate counter that sheds 99% of excess traffic in $<1\text{ms}$. |
| **Gate Drift** | $\text{StockGate Count} - \text{Available Stock}$ | Difference between in-memory gate counter and database stock. |
| **Active Reservations** | Count of `RESERVED` + `PAYMENT_PENDING` | Number of in-flight user reservations currently in payment grace period. |
| **Outbox Backlog** | Count of `OUTBOX` where `published_at IS NULL` | Unpublished transactional outbox domain events waiting for dispatch. |

### 3. Real-Time Charts (Chart.js)
- **Stock Doughnut**: Visual breakdown of Available vs. Reserved vs. Sold.
- **Rolling Time Series (Last 60s)**: High-frequency live line graph plotting Sold and Reserved movements in real-time.
- **Payments by Status**: Bar chart tracking `SUCCEEDED`, `FAILED`, `TIMEOUT`, `INITIATED`, `REFUNDED`.
- **Orders by Status**: Bar chart tracking `CREATED`, `PAYMENT_PENDING`, `CONFIRMED`, `SHIPPED`, `CANCELLED`.

### 4. Flash Sale Controller & Fault Injection
- **Load Controls**: Configure Total Users/Requests (e.g. 10,000), Stock (e.g. 100), Duplicate Request % (e.g. 2%), Payment Failure % (e.g. 5%), and Order Service Outage delay in seconds.
- **Run Flash Sale**: Triggers asynchronous load generation on background worker pool.
- **Reset State**: Atomically clears database tables and resets stock counter to the desired count.
- **Fault Toggles**:
  - **Payment Gateway Mode**: `NORMAL` (95% success), `SLOW` (2000ms latency), `DOWN` (network socket timeout / circuit breaker trip), `ALWAYS_DECLINE` (100% decline).
  - **Order Service Toggle**: `UP` / `DOWN` (pauses and resumes outbox publisher and command consumers).
- **Telemetry Bar**: Displays live Sent / Completed counters and Buy Now latency percentiles ($p50, p95, p99$).

### 5. Automated Demo Scenarios
Clicking any scenario button runs a preconfigured simulation and displays a **PASS/FAIL Checklist Banner** for easy verification:
1. **`LAST_ITEM`**: Stock = 1, 2 concurrent buyers. Proves exactly 1 buyer wins and 1 is rejected with no overselling.
2. **`FULL_10000`**: 10,000 requests, 100 stock, 95% payment success. Proves full pipeline execution, duplicate idempotency, and exact stock conservation.
3. **`DUPLICATES`**: 100 duplicate requests reusing idempotency keys. Proves 100% of duplicates receive the identical original reservation ID.
4. **`PAYMENT_FAILURES`**: 30% declined payments. Proves declined payments automatically release stock back to the pool to be re-sold to subsequent buyers.
5. **`ORDER_SERVICE_DOWN`**: Order service paused during payments. Proves outbox buffers events, and upon service recovery, all succeeded payments advance to `CONFIRMED` orders.
6. **`GATEWAY_DOWN`**: Gateway down. Proves payments fail gracefully, Resilience4j circuit breaker trips, and inventory remains protected.

### 6. Order Timeline & Saga Inspector
Enter any `orderId` to view its complete audit history, status transitions (`CREATED` $\rightarrow$ `RESERVED` $\rightarrow$ `PAYMENT_PENDING` $\rightarrow$ `CONFIRMED` $\rightarrow$ `SHIPPED`), linked payment details, and reservation expiration timers.

### 7. Real-Time Tables (Polled Every 2s)
- **Reservations**: ID, Customer ID, Order ID, Status, live countdown timer, Idempotency Key.
- **Orders**: Order ID, Customer, Status, Total Amount, Currency, Timestamp.
- **Payments**: Payment ID, Order ID, Status, Amount, Gateway Reference, Timestamp.
- **Event Log**: Transactional outbox event feed with publication status.

---

## 🛠️ REST API Specification

All simulation, reset, and fault endpoints are active when `salestorm.demo.enabled: true` in `application.yml`.

### Dashboard Read-Only APIs (`/api/v1/dashboard/*`)
- `GET /api/v1/dashboard/summary`: System metrics, stock conservation, grouped status counts, and invariant flag.
- `GET /api/v1/dashboard/reservations?status=&limit=50`: Latest reservations with countdown seconds.
- `GET /api/v1/dashboard/orders?limit=50`: Latest order records.
- `GET /api/v1/dashboard/payments?limit=50`: Latest payment attempts.
- `GET /api/v1/dashboard/events?limit=100`: Latest outbox event stream.
- `GET /api/v1/dashboard/orders/{orderId}/timeline`: Order status audit history, payment, and reservation details.
- `GET /api/v1/dashboard/stream`: Server-Sent Events (SSE) live metric push.

### Simulator & Fault Control APIs (`/api/v1/sim/*`)
- `POST /api/v1/sim/run`: Asynchronously runs flash sale with user volume, duplicates, and failure rates.
- `GET /api/v1/sim/status`: Current simulation progress and latency percentiles.
- `POST /api/v1/sim/reset`: Resets all database tables and stock gate counter.
- `POST /api/v1/sim/faults`: Injects gateway faults (`NORMAL`, `SLOW`, `DOWN`, `ALWAYS_DECLINE`) and order service outages.
- `GET /api/v1/sim/scenarios/{name}/run`: Executes automated verification scenarios.

---

## 🧪 Running Automated Tests

Run the complete 78-test suite (unit tests, resilience tests, saga tests, and 10k concurrent simulations):

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-27"
.\mvnw.cmd test
```

---

## 🤖 AI-Usage Note

The following components and artifacts were generated and verified during this implementation:
1. **Configuration**:
   - `src/main/resources/application.yml`: Added `salestorm.demo.enabled: true` and HikariCP connection pool settings.
2. **Backend Services & Controllers (`com.salestorm.dashboard`)**:
   - `DashboardController.java`: Read-only endpoints and Server-Sent Events stream.
   - `DashboardService.java`: High-performance grouped status aggregation and order timeline compiler.
   - `DashboardSummaryResponse.java`, `ReservationItemDto.java`, `OrderItemDto.java`, `PaymentItemDto.java`, `EventLogItemDto.java`, `OrderTimelineResponse.java`.
3. **Simulation & Fault Injection Engine (`com.salestorm.dashboard.sim`)**:
   - `SimulationController.java`: Endpoints for running load tests, resetting state, applying faults, and executing scenarios.
   - `SimulationService.java`: Multi-threaded load generator with $p50, p95, p99$ Buy Now latency calculations.
   - `ScenarioRunner.java`: 6 automated validation scenarios (`LAST_ITEM`, `FULL_10000`, `DUPLICATES`, `PAYMENT_FAILURES`, `ORDER_SERVICE_DOWN`, `GATEWAY_DOWN`).
   - `OutboxPublisher.java`: Added non-blocking pause/resume hook for simulating Order Service downtime.
4. **Frontend ("SALESTORM War Room")**:
   - `src/main/resources/static/dashboard.html`: Cyberpunk responsive war room layout with Top Bar, KPI cards, Charts, Controls, Scenarios, Order Inspector, and Tables.
   - `src/main/resources/static/dashboard.css`: Glassmorphic styling, neon glow accents, and responsive grids.
   - `src/main/resources/static/dashboard.js`: SSE client listener, Chart.js integrations (doughnut, live rolling line, bar charts), table polling, and interactive scenario checklist renderer.
5. **Testing Suite**:
   - `DashboardServiceTest.java`: Unit tests verifying summary metrics and invariant check.
   - `ScenarioRunnerIntegrationTest.java`: Integration tests executing `LAST_ITEM` and `FULL_10000` scenarios.
