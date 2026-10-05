let currentOrder = null;
let currentReservation = null;
let timerInterval = null;
let isSimulationRunning = false;

document.addEventListener('DOMContentLoaded', () => {
    initDefaults();
    pollStats();
    setInterval(pollStats, 1500);

    // Event listeners
    document.getElementById('btn-buy').addEventListener('click', handleBuyNow);
    document.getElementById('btn-pay').addEventListener('click', handlePayNow);
    document.getElementById('btn-reset-stock').addEventListener('click', handleResetStock);
    document.getElementById('btn-launch-sim').addEventListener('click', handleLaunchSim);
    document.getElementById('btn-gen-idemp').addEventListener('click', generateNewIdempKey);

    // Preset pills
    document.querySelectorAll('.pill-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            document.querySelectorAll('.pill-btn').forEach(b => b.classList.remove('active'));
            e.target.classList.add('active');
            const reqs = e.target.dataset.reqs;
            document.getElementById('sim-requests-range').value = reqs;
            document.getElementById('sim-requests-val').textContent = Number(reqs).toLocaleString();
        });
    });

    document.getElementById('sim-requests-range').addEventListener('input', (e) => {
        document.getElementById('sim-requests-val').textContent = Number(e.target.value).toLocaleString();
        document.querySelectorAll('.pill-btn').forEach(b => b.classList.remove('active'));
    });

    logMessage('System initialized. Ready for flash sale traffic.', 'info');
});

function initDefaults() {
    generateNewIdempKey();
    document.getElementById('input-customer-id').value = Math.floor(10000 + Math.random() * 90000);
}

function generateNewIdempKey() {
    const key = 'IDEMP-' + Math.random().toString(36).substring(2, 9).toUpperCase();
    document.getElementById('input-idemp-key').value = key;
}

async function pollStats() {
    try {
        const res = await fetch('/api/v1/dashboard/stats');
        if (!res.ok) return;
        const data = await res.json();

        // Update Top Stats
        document.getElementById('stat-available').textContent = data.available;
        document.getElementById('stat-reserved').textContent = data.reserved;
        document.getElementById('stat-sold').textContent = data.sold;
        document.getElementById('stat-gate').textContent = data.gateCounter;
        document.getElementById('stat-revenue').textContent = '$' + Number(data.totalRevenue || 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
        document.getElementById('stat-orders').textContent = data.confirmedOrders;

        // Progress Bar
        const total = data.totalStock || 100;
        const soldPct = Math.min(100, Math.round((data.sold / total) * 100));
        document.getElementById('stock-progress-fill').style.width = soldPct + '%';
        document.getElementById('stock-progress-label').textContent = `${data.available} / ${total} available (${soldPct}% sold)`;

        // In-memory gate badge
        const gateBadge = document.getElementById('gate-sync-status');
        if (data.gateCounter === data.available) {
            gateBadge.textContent = 'Gatekeeper in sync';
            gateBadge.style.color = '#34d399';
        } else {
            gateBadge.textContent = 'Syncing Gate (' + data.gateCounter + ')';
            gateBadge.style.color = '#fbbf24';
        }

        // Disable buy button if sold out
        const buyBtn = document.getElementById('btn-buy');
        if (data.available <= 0 && data.reserved <= 0 && !currentReservation) {
            buyBtn.disabled = true;
            buyBtn.innerHTML = '<span>❌ SOLD OUT</span>';
        } else if (!currentReservation) {
            buyBtn.disabled = false;
            buyBtn.innerHTML = '<span>⚡ Instant Buy ($99.99)</span>';
        }
    } catch (e) {
        // quiet poll error
    }
}

async function handleBuyNow() {
    const customerId = document.getElementById('input-customer-id').value;
    const idempotencyKey = document.getElementById('input-idemp-key').value;
    const couponCode = document.getElementById('input-coupon').value;

    const buyBtn = document.getElementById('btn-buy');
    buyBtn.disabled = true;
    buyBtn.innerHTML = '<span>Reserving unit...</span>';

    logMessage(`Initiating Buy Now: Cust #${customerId}, Key: ${idempotencyKey}`, 'info');

    try {
        const res = await fetch('/api/v1/checkout/buy', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-Customer-Id': customerId,
                'Idempotency-Key': idempotencyKey
            },
            body: JSON.stringify({
                productId: 1,
                quantity: 1,
                couponCode: couponCode ? couponCode.trim() : null
            })
        });

        const data = await res.json();

        if (res.status === 201 || res.status === 200) {
            currentOrder = data;
            currentReservation = data.reservationId;
            logMessage(`Stock Reserved! Reservation: ${data.reservationId.substring(0, 8)}... Total: $${data.totalAmount}`, 'success');

            // Show active reservation panel
            showActiveReservation(data);
            pollStats();
        } else {
            logMessage(`Buy Failed (${res.status}): ${data.message || data.error || 'Sold out or unavailable'}`, 'error');
            buyBtn.disabled = false;
            buyBtn.innerHTML = '<span>⚡ Instant Buy ($99.99)</span>';
        }
    } catch (err) {
        logMessage(`Network Error: ${err.message}`, 'error');
        buyBtn.disabled = false;
        buyBtn.innerHTML = '<span>⚡ Instant Buy ($99.99)</span>';
    }
}

function showActiveReservation(orderData) {
    document.getElementById('reservation-panel').style.display = 'block';
    document.getElementById('res-order-id').textContent = orderData.orderId;
    document.getElementById('res-amount').textContent = '$' + orderData.totalAmount;
    document.getElementById('res-id').textContent = orderData.reservationId.substring(0, 13) + '...';

    // Start 300s countdown timer
    let secondsLeft = 300;
    if (timerInterval) clearInterval(timerInterval);
    timerInterval = setInterval(() => {
        secondsLeft--;
        const mins = Math.floor(secondsLeft / 60);
        const secs = secondsLeft % 60;
        document.getElementById('res-timer').textContent = `${mins}:${secs < 10 ? '0' : ''}${secs}`;
        if (secondsLeft <= 0) {
            clearInterval(timerInterval);
            document.getElementById('res-timer').textContent = 'EXPIRED';
            logMessage('Reservation TTL expired. Stock released.', 'warn');
            resetReservationUI();
            pollStats();
        }
    }, 1000);

    updateSagaStepper('RESERVED');
}

async function handlePayNow() {
    if (!currentOrder) return;

    const payBtn = document.getElementById('btn-pay');
    payBtn.disabled = true;
    payBtn.innerHTML = '<span>Processing Payment...</span>';

    const customerId = document.getElementById('input-customer-id').value;
    const payIdempKey = 'PAY-' + document.getElementById('input-idemp-key').value;

    logMessage(`Invoking Payment Gateway for Order ${currentOrder.orderId.substring(0, 8)}...`, 'info');
    updateSagaStepper('PAYMENT_PENDING');

    try {
        const res = await fetch('/api/v1/checkout/pay', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-Customer-Id': customerId,
                'Idempotency-Key': payIdempKey
            },
            body: JSON.stringify({
                orderId: currentOrder.orderId,
                amount: currentOrder.totalAmount
            })
        });

        const data = await res.json();

        if (res.ok && data.status === 'SUCCEEDED') {
            if (timerInterval) clearInterval(timerInterval);
            logMessage(`Payment Succeeded! Gateway Ref: ${data.gatewayRef}. Order CONFIRMED!`, 'success');
            updateSagaStepper('CONFIRMED');

            setTimeout(() => {
                updateSagaStepper('SHIPPED');
                logMessage(`Mock Shipment dispatched for Order ${currentOrder.orderId.substring(0, 8)}!`, 'info');
            }, 1200);

            payBtn.innerHTML = '<span>✅ Paid & Confirmed!</span>';
            payBtn.className = 'btn-success';

            setTimeout(() => {
                resetReservationUI();
                generateNewIdempKey();
                pollStats();
            }, 3500);
        } else {
            logMessage(`Payment Declined/Failed: ${data.failureReason || 'Declined'}`, 'error');
            payBtn.disabled = false;
            payBtn.innerHTML = '<span>💳 Retry Payment</span>';
        }
    } catch (err) {
        logMessage(`Payment Network Error: ${err.message}`, 'error');
        payBtn.disabled = false;
        payBtn.innerHTML = '<span>💳 Retry Payment</span>';
    }
}

function updateSagaStepper(status) {
    const steps = ['CREATED', 'RESERVED', 'PAYMENT_PENDING', 'CONFIRMED', 'SHIPPED'];
    const currentIdx = steps.indexOf(status);

    steps.forEach((step, idx) => {
        const el = document.getElementById('step-' + step.toLowerCase());
        if (!el) return;
        el.classList.remove('active', 'completed');
        if (idx < currentIdx) {
            el.classList.add('completed');
        } else if (idx === currentIdx) {
            el.classList.add('active');
        }
    });
}

function resetReservationUI() {
    currentOrder = null;
    currentReservation = null;
    if (timerInterval) clearInterval(timerInterval);
    document.getElementById('reservation-panel').style.display = 'none';

    const payBtn = document.getElementById('btn-pay');
    payBtn.disabled = false;
    payBtn.innerHTML = '<span>💳 Pay Now ($99.99)</span>';
    payBtn.className = 'btn-primary';

    const buyBtn = document.getElementById('btn-buy');
    buyBtn.disabled = false;
    buyBtn.innerHTML = '<span>⚡ Instant Buy ($99.99)</span>';
}

async function handleResetStock() {
    try {
        const res = await fetch('/api/v1/dashboard/reset?stock=100', { method: 'POST' });
        const data = await res.json();
        logMessage(data.message || 'Stock reset to 100', 'warn');
        resetReservationUI();
        generateNewIdempKey();
        pollStats();
    } catch (err) {
        logMessage(`Reset error: ${err.message}`, 'error');
    }
}

async function handleLaunchSim() {
    if (isSimulationRunning) return;

    const requests = parseInt(document.getElementById('sim-requests-range').value) || 1000;
    const concurrency = 50;

    isSimulationRunning = true;
    const simBtn = document.getElementById('btn-launch-sim');
    simBtn.disabled = true;
    simBtn.innerHTML = `<span>⚡ Running ${requests.toLocaleString()} Concurrent Requests...</span>`;

    logMessage(`[BENCHMARK] Launching ${requests.toLocaleString()} concurrent requests (50 worker threads)...`, 'warn');

    try {
        const res = await fetch(`/api/v1/dashboard/simulate?requests=${requests}&concurrency=${concurrency}`, {
            method: 'POST'
        });

        const data = await res.json();

        // Render results box
        document.getElementById('sim-results').style.display = 'block';
        document.getElementById('res-throughput').textContent = data.requestsPerSecond + ' req/s';
        document.getElementById('res-duration').textContent = (data.durationMs / 1000).toFixed(2) + ' s';
        document.getElementById('res-p95').textContent = data.p95LatencyMs + ' ms';
        document.getElementById('res-p99').textContent = data.p99LatencyMs + ' ms';
        document.getElementById('res-sold').textContent = data.finalSold + ' units';
        document.getElementById('res-shed').textContent = data.rejectedRequests.toLocaleString();

        const consEl = document.getElementById('res-conservation');
        if (data.stockConservationPassed) {
            consEl.textContent = '100% INVARIANT [PASS]';
            consEl.style.color = '#34d399';
        } else {
            consEl.textContent = 'FAILED';
            consEl.style.color = '#ef4444';
        }

        logMessage(`[BENCHMARK FINISHED] ${data.requestsPerSecond} req/sec | Sold: ${data.finalSold} | Shed: ${data.rejectedRequests} | p95: ${data.p95LatencyMs}ms`, 'success');
        pollStats();
    } catch (err) {
        logMessage(`Simulation error: ${err.message}`, 'error');
    } finally {
        isSimulationRunning = false;
        simBtn.disabled = false;
        simBtn.innerHTML = '<span>🚀 Launch Concurrent Stress Test</span>';
    }
}

function logMessage(msg, level = 'info') {
    const terminal = document.getElementById('terminal-logs');
    if (!terminal) return;

    const time = new Date().toLocaleTimeString();
    const entry = document.createElement('div');
    entry.className = `log-entry ${level}`;
    entry.innerHTML = `<span class="time">[${time}]</span> ${msg}`;
    terminal.appendChild(entry);
    terminal.scrollTop = terminal.scrollHeight;
}
