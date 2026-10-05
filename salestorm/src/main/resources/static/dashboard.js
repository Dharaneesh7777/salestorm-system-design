/**
 * SALESTORM War Room - Real-Time Dashboard Client Application
 */

// Charts instances
let doughnutChart, lineTimeSeriesChart, paymentsBarChart, ordersBarChart;

// Rolling time-series history (last 60s)
const timeSeriesHistory = {
    labels: [],
    sold: [],
    reserved: []
};

// Polling interval identifiers
let tablePollInterval = null;
let simStatusPollInterval = null;
let sseSource = null;

// Fault states
let currentGatewayMode = 'NORMAL';
let currentOrderServiceDown = false;

document.addEventListener('DOMContentLoaded', () => {
    initCharts();
    initSseStream();
    startTablePolling();
    initEventListeners();
});

/* =========================================================================
   1. Server-Sent Events (SSE) Stream
   ========================================================================= */
function initSseStream() {
    const statusEl = document.getElementById('sse-status-badge');

    if (sseSource) {
        sseSource.close();
    }

    try {
        sseSource = new EventSource('/api/v1/dashboard/stream');

        sseSource.addEventListener('summary', (event) => {
            try {
                const summary = JSON.parse(event.data);
                updateSummaryUI(summary);
                updateCharts(summary);
                updateTimeSeries(summary);
            } catch (err) {
                console.error('Error parsing SSE summary payload:', err);
            }
        });

        sseSource.onopen = () => {
            statusEl.className = 'indicator-badge sse-badge';
            statusEl.innerHTML = '<span class="dot-pulse"></span><span>Live Stream: Connected</span>';
        };

        sseSource.onerror = () => {
            statusEl.className = 'indicator-badge sse-badge disconnected';
            statusEl.innerHTML = '<span class="dot-pulse"></span><span>Live Stream: Reconnecting...</span>';
            // Auto-reconnection is natively handled by EventSource
        };
    } catch (e) {
        console.warn('SSE not supported or failed to initialize, fallback to polling');
    }
}

/* =========================================================================
   2. UI Summary & Invariant Updates
   ========================================================================= */
function updateSummaryUI(s) {
    // Invariant Badge
    const invBadge = document.getElementById('invariant-status-badge');
    if (s.invariantOk) {
        invBadge.className = 'indicator-badge invariant-badge';
        invBadge.textContent = 'Invariant OK: available + reserved + sold == total';
    } else {
        invBadge.className = 'indicator-badge invariant-badge broken';
        invBadge.textContent = 'ALERT: INVARIANT VIOLATION DETECTED!';
    }

    // KPI Big Numbers
    document.getElementById('kpi-available').textContent = s.available;
    document.getElementById('kpi-reserved').textContent = s.reserved;
    document.getElementById('kpi-sold').textContent = s.sold;
    document.getElementById('kpi-gate').textContent = s.redisGateCount;
    document.getElementById('kpi-drift').textContent = (s.drift > 0 ? '+' : '') + s.drift;
    document.getElementById('kpi-active-res').textContent = s.activeReservations;
    document.getElementById('kpi-outbox').textContent = s.outboxUnpublished;

    // Progress Bars
    const total = s.totalStock || 100;
    document.getElementById('bar-avail').style.width = ((s.available / total) * 100) + '%';
    document.getElementById('bar-res').style.width = ((s.reserved / total) * 100) + '%';
    document.getElementById('bar-sold').style.width = ((s.sold / total) * 100) + '%';
}

/* =========================================================================
   3. Chart.js Visualizations
   ========================================================================= */
function initCharts() {
    // 1. Doughnut Chart
    const ctxDoughnut = document.getElementById('chart-doughnut').getContext('2d');
    doughnutChart = new Chart(ctxDoughnut, {
        type: 'doughnut',
        data: {
            labels: ['Available', 'Reserved', 'Sold'],
            datasets: [{
                data: [100, 0, 0],
                backgroundColor: ['#00f0ff', '#f59e0b', '#ec4899'],
                borderWidth: 0
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { position: 'bottom', labels: { color: '#94a3b8', font: { size: 10 } } }
            },
            cutout: '70%'
        }
    });

    // 2. Rolling Time Series Line Chart (last 60s)
    const ctxLine = document.getElementById('chart-timeseries').getContext('2d');
    lineTimeSeriesChart = new Chart(ctxLine, {
        type: 'line',
        data: {
            labels: [],
            datasets: [
                {
                    label: 'Sold',
                    data: [],
                    borderColor: '#ec4899',
                    backgroundColor: 'rgba(236, 72, 153, 0.1)',
                    borderWidth: 2,
                    tension: 0.3,
                    fill: true
                },
                {
                    label: 'Reserved',
                    data: [],
                    borderColor: '#f59e0b',
                    backgroundColor: 'rgba(245, 158, 11, 0.1)',
                    borderWidth: 2,
                    tension: 0.3,
                    fill: true
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: false,
            scales: {
                x: { display: false },
                y: { beginAtZero: true, grid: { color: 'rgba(255,255,255,0.05)' }, ticks: { color: '#64748b' } }
            },
            plugins: {
                legend: { labels: { color: '#94a3b8', font: { size: 10 } } }
            }
        }
    });

    // 3. Payments Bar Chart
    const ctxPayments = document.getElementById('chart-payments').getContext('2d');
    paymentsBarChart = new Chart(ctxPayments, {
        type: 'bar',
        data: {
            labels: ['SUCCEEDED', 'FAILED', 'TIMEOUT', 'INITIATED', 'REFUNDED'],
            datasets: [{
                label: 'Payments',
                data: [0, 0, 0, 0, 0],
                backgroundColor: ['#10b981', '#f43f5e', '#f59e0b', '#3b82f6', '#a855f7'],
                borderRadius: 4
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            scales: {
                x: { grid: { display: false }, ticks: { color: '#94a3b8', font: { size: 9 } } },
                y: { beginAtZero: true, grid: { color: 'rgba(255,255,255,0.05)' }, ticks: { color: '#64748b' } }
            },
            plugins: { legend: { display: false } }
        }
    });

    // 4. Orders Bar Chart
    const ctxOrders = document.getElementById('chart-orders').getContext('2d');
    ordersBarChart = new Chart(ctxOrders, {
        type: 'bar',
        data: {
            labels: ['CREATED', 'PAYMENT_PENDING', 'CONFIRMED', 'SHIPPED', 'CANCELLED'],
            datasets: [{
                label: 'Orders',
                data: [0, 0, 0, 0, 0],
                backgroundColor: ['#3b82f6', '#f59e0b', '#10b981', '#00f0ff', '#f43f5e'],
                borderRadius: 4
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            scales: {
                x: { grid: { display: false }, ticks: { color: '#94a3b8', font: { size: 9 } } },
                y: { beginAtZero: true, grid: { color: 'rgba(255,255,255,0.05)' }, ticks: { color: '#64748b' } }
            },
            plugins: { legend: { display: false } }
        }
    });
}

function updateCharts(s) {
    if (doughnutChart) {
        doughnutChart.data.datasets[0].data = [s.available, s.reserved, s.sold];
        doughnutChart.update();
    }

    if (paymentsBarChart) {
        const p = s.paymentsByStatus || {};
        paymentsBarChart.data.datasets[0].data = [
            p['SUCCEEDED'] || 0,
            p['FAILED'] || 0,
            p['TIMEOUT'] || 0,
            p['INITIATED'] || 0,
            p['REFUNDED'] || 0
        ];
        paymentsBarChart.update();
    }

    if (ordersBarChart) {
        const o = s.ordersByStatus || {};
        ordersBarChart.data.datasets[0].data = [
            o['CREATED'] || 0,
            o['PAYMENT_PENDING'] || 0,
            o['CONFIRMED'] || 0,
            o['SHIPPED'] || 0,
            o['CANCELLED'] || 0
        ];
        ordersBarChart.update();
    }
}

function updateTimeSeries(s) {
    const timeLabel = new Date().toLocaleTimeString();
    timeSeriesHistory.labels.push(timeLabel);
    timeSeriesHistory.sold.push(s.sold);
    timeSeriesHistory.reserved.push(s.reserved);

    // Keep last 60 seconds
    if (timeSeriesHistory.labels.length > 60) {
        timeSeriesHistory.labels.shift();
        timeSeriesHistory.sold.shift();
        timeSeriesHistory.reserved.shift();
    }

    if (lineTimeSeriesChart) {
        lineTimeSeriesChart.data.labels = timeSeriesHistory.labels;
        lineTimeSeriesChart.data.datasets[0].data = timeSeriesHistory.sold;
        lineTimeSeriesChart.data.datasets[1].data = timeSeriesHistory.reserved;
        lineTimeSeriesChart.update();
    }
}

/* =========================================================================
   4. Table Polling (Every 2s)
   ========================================================================= */
function startTablePolling() {
    pollActiveTable();
    tablePollInterval = setInterval(pollActiveTable, 2000);
}

function pollActiveTable() {
    const activeTab = document.querySelector('.tab-btn.active').dataset.tab;
    switch (activeTab) {
        case 'reservations': fetchReservations(); break;
        case 'orders': fetchOrders(); break;
        case 'payments': fetchPayments(); break;
        case 'events': fetchEvents(); break;
    }
}

async function fetchReservations() {
    try {
        const res = await fetch('/api/v1/dashboard/reservations?limit=50');
        const data = await res.json();
        const tbody = document.getElementById('table-reservations-body');
        tbody.innerHTML = data.map(r => `
            <tr>
                <td>${r.reservationId ? r.reservationId.substring(0, 8) + '...' : '-'}</td>
                <td>${r.customerId}</td>
                <td>${r.orderId ? r.orderId.substring(0, 8) + '...' : '-'}</td>
                <td>${renderStatusChip(r.status)}</td>
                <td>${r.expiresInSeconds > 0 ? r.expiresInSeconds + 's' : 'EXPIRED'}</td>
                <td><small>${r.idempotencyKey || '-'}</small></td>
            </tr>
        `).join('');
    } catch (e) {}
}

async function fetchOrders() {
    try {
        const res = await fetch('/api/v1/dashboard/orders?limit=50');
        const data = await res.json();
        const tbody = document.getElementById('table-orders-body');
        tbody.innerHTML = data.map(o => `
            <tr>
                <td><strong>${o.orderId}</strong></td>
                <td>${o.customerId}</td>
                <td>${renderStatusChip(o.status)}</td>
                <td>$${Number(o.totalAmount).toFixed(2)}</td>
                <td>${o.currency}</td>
                <td>${formatTime(o.createdAt)}</td>
            </tr>
        `).join('');
    } catch (e) {}
}

async function fetchPayments() {
    try {
        const res = await fetch('/api/v1/dashboard/payments?limit=50');
        const data = await res.json();
        const tbody = document.getElementById('table-payments-body');
        tbody.innerHTML = data.map(p => `
            <tr>
                <td>${p.paymentId ? p.paymentId.substring(0, 8) + '...' : '-'}</td>
                <td>${p.orderId ? p.orderId.substring(0, 8) + '...' : '-'}</td>
                <td>${renderStatusChip(p.status)}</td>
                <td>$${Number(p.amount).toFixed(2)}</td>
                <td>${p.gatewayRef || '-'}</td>
                <td>${formatTime(p.createdAt)}</td>
            </tr>
        `).join('');
    } catch (e) {}
}

async function fetchEvents() {
    try {
        const res = await fetch('/api/v1/dashboard/events?limit=100');
        const data = await res.json();
        const tbody = document.getElementById('table-events-body');
        tbody.innerHTML = data.map(e => `
            <tr>
                <td>${formatTime(e.createdAt)}</td>
                <td><strong>${e.type}</strong></td>
                <td>${e.aggregateType}</td>
                <td>${e.orderId ? e.orderId.substring(0, 8) + '...' : '-'}</td>
                <td>${e.published ? '<span class="status-chip success">PUBLISHED</span>' : '<span class="status-chip pending">PENDING</span>'}</td>
            </tr>
        `).join('');
    } catch (e) {}
}

function renderStatusChip(status) {
    if (!status) return '-';
    const s = status.toUpperCase();
    if (['CONFIRMED', 'SUCCEEDED', 'SHIPPED', 'DELIVERED', 'DONE'].includes(s)) {
        return `<span class="status-chip success">${s}</span>`;
    }
    if (['RESERVED', 'PAYMENT_PENDING', 'INITIATED', 'PROCESSING'].includes(s)) {
        return `<span class="status-chip pending">${s}</span>`;
    }
    if (['FAILED', 'DECLINED', 'CANCELLED', 'RELEASED', 'TIMEOUT'].includes(s)) {
        return `<span class="status-chip failed">${s}</span>`;
    }
    return `<span class="status-chip neutral">${s}</span>`;
}

function formatTime(instantStr) {
    if (!instantStr) return '-';
    try {
        return new Date(instantStr).toLocaleTimeString();
    } catch (e) {
        return instantStr;
    }
}

/* =========================================================================
   5. Event Listeners, Control Panel & Simulation
   ========================================================================= */
function initEventListeners() {
    // Tab switching
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
            document.querySelectorAll('.table-section').forEach(t => t.style.display = 'none');
            e.target.classList.add('active');
            const tabId = 'tab-content-' + e.target.dataset.tab;
            document.getElementById(tabId).style.display = 'block';
            pollActiveTable();
        });
    });

    // Run Flash Sale button
    document.getElementById('btn-run-sim').addEventListener('click', handleRunFlashSale);

    // Reset button
    document.getElementById('btn-reset-all').addEventListener('click', handleResetAll);

    // Fault Toggles
    document.querySelectorAll('.btn-gw-fault').forEach(btn => {
        btn.addEventListener('click', (e) => {
            document.querySelectorAll('.btn-gw-fault').forEach(b => b.classList.remove('active'));
            e.target.classList.add('active');
            currentGatewayMode = e.target.dataset.mode;
            applyFaults();
        });
    });

    document.getElementById('toggle-order-service').addEventListener('click', (e) => {
        currentOrderServiceDown = !currentOrderServiceDown;
        e.target.textContent = currentOrderServiceDown ? 'Order Service: DOWN' : 'Order Service: UP';
        e.target.className = currentOrderServiceDown ? 'fault-btn-toggle active' : 'fault-btn-toggle';
        applyFaults();
    });

    // Scenarios Buttons
    document.querySelectorAll('.scenario-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            const scenario = e.currentTarget.dataset.scenario;
            handleRunScenario(scenario);
        });
    });

    // Order Timeline Lookup
    document.getElementById('btn-lookup-order').addEventListener('click', handleOrderLookup);
}

async function handleRunFlashSale() {
    const users = parseInt(document.getElementById('input-users').value) || 10000;
    const stock = parseInt(document.getElementById('input-stock').value) || 100;
    const dupPct = parseInt(document.getElementById('input-dup-pct').value) || 2;
    const failPct = parseInt(document.getElementById('input-fail-pct').value) || 5;
    const downSecs = parseInt(document.getElementById('input-down-secs').value) || 0;

    const btn = document.getElementById('btn-run-sim');
    btn.disabled = true;
    btn.textContent = '🚀 Flash Sale Running...';

    try {
        const res = await fetch('/api/v1/sim/run', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                users: users,
                stock: stock,
                duplicatePercent: dupPct,
                paymentFailPercent: failPct,
                orderServiceDownSeconds: downSecs
            })
        });
        const data = await res.json();
        startSimStatusPolling(users);
    } catch (e) {
        console.error('Failed to run flash sale:', e);
        btn.disabled = false;
        btn.textContent = '⚡ Run Flash Sale';
    }
}

function startSimStatusPolling(totalUsers) {
    if (simStatusPollInterval) clearInterval(simStatusPollInterval);

    simStatusPollInterval = setInterval(async () => {
        try {
            const res = await fetch('/api/v1/sim/status');
            const s = await res.json();

            // Progress bar
            const completed = s.completed || 0;
            const pct = Math.min(100, Math.round((completed / totalUsers) * 100));
            document.getElementById('sim-progress-bar').style.width = pct + '%';
            document.getElementById('sim-progress-label').textContent = `${completed.toLocaleString()} / ${totalUsers.toLocaleString()} (${pct}%)`;

            // Telemetry numbers
            document.getElementById('sim-sent').textContent = s.sent.toLocaleString();
            document.getElementById('sim-completed').textContent = s.completed.toLocaleString();
            document.getElementById('sim-p50').textContent = s.p50 + 'ms';
            document.getElementById('sim-p95').textContent = s.p95 + 'ms';
            document.getElementById('sim-p99').textContent = s.p99 + 'ms';

            if (!s.running && completed >= totalUsers) {
                clearInterval(simStatusPollInterval);
                const btn = document.getElementById('btn-run-sim');
                btn.disabled = false;
                btn.textContent = '⚡ Run Flash Sale';
            }
        } catch (e) {}
    }, 500);
}

async function handleResetAll() {
    const stock = parseInt(document.getElementById('input-stock').value) || 100;
    try {
        await fetch('/api/v1/sim/reset', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ stock: stock })
        });
        document.getElementById('sim-progress-bar').style.width = '0%';
        document.getElementById('sim-progress-label').textContent = '0 / 0 (0%)';
        pollActiveTable();
    } catch (e) {
        console.error('Reset failed:', e);
    }
}

async function applyFaults() {
    try {
        await fetch('/api/v1/sim/faults', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                paymentGatewayMode: currentGatewayMode,
                orderServiceDown: currentOrderServiceDown
            })
        });
    } catch (e) {
        console.error('Applying faults failed:', e);
    }
}

/* =========================================================================
   6. Scenario Runner & PASS/FAIL Checklist
   ========================================================================= */
async function handleRunScenario(scenarioName) {
    const banner = document.getElementById('scenario-result-banner');
    banner.style.display = 'block';
    banner.className = 'scenario-banner';
    banner.innerHTML = `<strong>Running scenario: ${scenarioName}...</strong>`;

    try {
        const res = await fetch(`/api/v1/sim/scenarios/${scenarioName}/run`);
        const result = await res.json();

        banner.className = result.overallPass ? 'scenario-banner pass' : 'scenario-banner fail';
        banner.innerHTML = `
            <div class="banner-header">
                <span>SCENARIO: ${result.name}</span>
                <span>${result.overallPass ? '✅ OVERALL PASS' : '❌ FAILED'}</span>
            </div>
            <div class="checklist">
                ${result.checks.map(c => `
                    <div class="check-item ${c.pass ? 'pass' : 'fail'}">
                        <span>${c.pass ? '✓' : '✗'} ${c.label}</span>
                        <span>[Actual: ${c.actual}]</span>
                    </div>
                `).join('')}
            </div>
        `;
        pollActiveTable();
    } catch (e) {
        banner.className = 'scenario-banner fail';
        banner.innerHTML = `<strong>Failed to execute scenario: ${e.message}</strong>`;
    }
}

/* =========================================================================
   7. Order Timeline Lookup
   ========================================================================= */
async function handleOrderLookup() {
    const orderId = document.getElementById('lookup-order-id').value.trim();
    if (!orderId) return;

    const timelineBox = document.getElementById('order-timeline-box');
    timelineBox.style.display = 'block';
    timelineBox.innerHTML = '<p>Loading timeline...</p>';

    try {
        const res = await fetch(`/api/v1/dashboard/orders/${orderId}/timeline`);
        if (!res.ok) {
            timelineBox.innerHTML = `<p style="color: #f43f5e;">Order not found: ${orderId}</p>`;
            return;
        }
        const data = await res.json();

        let timelineHtml = `
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
                <div>
                    <strong>Order ID:</strong> ${data.orderId} |
                    <strong>Customer:</strong> #${data.customerId} |
                    <strong>Total:</strong> $${Number(data.totalAmount).toFixed(2)} ${data.currency}
                </div>
                <div>Status: ${renderStatusChip(data.status)}</div>
            </div>
            <div class="timeline-stepper">
                ${(data.history || []).map(h => `
                    <div class="timeline-node">
                        <div class="status">${h.newStatus}</div>
                        <div class="time">${formatTime(h.changedAt)}</div>
                        <div style="font-size: 10px; color: #94a3b8;">${h.reason || 'Saga transition'}</div>
                    </div>
                `).join('')}
            </div>
        `;

        if (data.payment) {
            timelineHtml += `
                <div style="margin-top: 10px; font-size: 11px; color: #94a3b8;">
                    <strong>Payment:</strong> ID: ${data.payment.paymentId} | Status: ${data.payment.status} | Gateway Ref: ${data.payment.gatewayRef || 'N/A'}
                </div>
            `;
        }

        timelineBox.innerHTML = timelineHtml;
    } catch (e) {
        timelineBox.innerHTML = `<p style="color: #f43f5e;">Error: ${e.message}</p>`;
    }
}
