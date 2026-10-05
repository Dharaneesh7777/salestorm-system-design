package com.salestorm.dashboard.application;

import com.salestorm.dashboard.api.dto.*;
import com.salestorm.inventory.domain.InventoryRepository;
import com.salestorm.inventory.domain.StockGate;
import com.salestorm.inventory.infrastructure.entity.InventoryEntity;
import com.salestorm.inventory.infrastructure.entity.InventoryReservationEntity;
import com.salestorm.order.domain.OrderStatus;
import com.salestorm.order.infrastructure.entity.OrderEntity;
import com.salestorm.order.infrastructure.entity.OrderStatusHistoryEntity;
import com.salestorm.payment.domain.PaymentStatus;
import com.salestorm.payment.infrastructure.entity.PaymentEntity;
import com.salestorm.common.infrastructure.entity.OutboxEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final InventoryRepository inventoryRepository;
    private final StockGate stockGate;

    @PersistenceContext
    private EntityManager entityManager;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public DashboardService(InventoryRepository inventoryRepository, StockGate stockGate) {
        this.inventoryRepository = inventoryRepository;
        this.stockGate = stockGate;
    }

    public DashboardSummaryResponse getSummary() {
        InventoryEntity inv = inventoryRepository.findByProductId(1L)
                .orElse(new InventoryEntity(1L, 100, 100, 0, 0));

        int totalStock = inv.getTotalStock() != null ? inv.getTotalStock() : 100;
        int available = inv.getAvailableQuantity() != null ? inv.getAvailableQuantity() : 0;
        int reserved = inv.getReservedQuantity() != null ? inv.getReservedQuantity() : 0;
        int sold = inv.getSoldQuantity() != null ? inv.getSoldQuantity() : 0;

        int redisGateCount = stockGate.getAvailable(1L);
        int drift = redisGateCount - available;

        // Grouped status counts
        Map<String, Long> ordersByStatus = getGroupedCounts("SELECT o.status, COUNT(o) FROM OrderEntity o GROUP BY o.status");
        Map<String, Long> paymentsByStatus = getGroupedCounts("SELECT p.status, COUNT(p) FROM PaymentEntity p GROUP BY p.status");
        Map<String, Long> sagaByState = getGroupedCounts("SELECT s.status, COUNT(s) FROM SagaStateEntity s GROUP BY s.status");

        // Active reservations (RESERVED or PAYMENT_PENDING)
        Long activeRes = (Long) entityManager.createQuery(
                "SELECT COUNT(r) FROM InventoryReservationEntity r WHERE r.status IN ('RESERVED', 'PAYMENT_PENDING')"
        ).getSingleResult();

        // Unpublished outbox count
        Long outboxCount = (Long) entityManager.createQuery(
                "SELECT COUNT(o) FROM OutboxEntity o WHERE o.publishedAt IS NULL"
        ).getSingleResult();

        boolean invariantOk = (available >= 0 && reserved >= 0 && sold >= 0 && (available + reserved + sold == totalStock));

        return new DashboardSummaryResponse(
                inv.getProductId() != null ? inv.getProductId() : 1L,
                totalStock,
                available,
                reserved,
                sold,
                redisGateCount,
                drift,
                activeRes != null ? activeRes.intValue() : 0,
                ordersByStatus,
                paymentsByStatus,
                sagaByState,
                outboxCount != null ? outboxCount.intValue() : 0,
                invariantOk
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> getGroupedCounts(String jpql) {
        Map<String, Long> counts = new LinkedHashMap<>();
        List<Object[]> rows = entityManager.createQuery(jpql).getResultList();
        for (Object[] row : rows) {
            String key = String.valueOf(row[0]);
            Long val = (Long) row[1];
            counts.put(key, val);
        }
        return counts;
    }

    @SuppressWarnings("unchecked")
    public List<ReservationItemDto> getReservations(String status, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        String jpql = "SELECT r FROM InventoryReservationEntity r";
        if (status != null && !status.isBlank()) {
            jpql += " WHERE r.status = :status";
        }
        jpql += " ORDER BY r.createdAt DESC";

        var query = entityManager.createQuery(jpql, InventoryReservationEntity.class).setMaxResults(boundedLimit);
        if (status != null && !status.isBlank()) {
            query.setParameter("status", status.trim().toUpperCase());
        }

        List<InventoryReservationEntity> list = query.getResultList();
        Instant now = Instant.now();

        return list.stream().map(r -> {
            long remainingSec = r.getExpiresAt() != null ? Math.max(0, Duration.between(now, r.getExpiresAt()).toSeconds()) : 0;
            return new ReservationItemDto(
                    r.getReservationId(),
                    r.getProductId(),
                    r.getCustomerId(),
                    r.getOrderId(),
                    r.getQuantity(),
                    r.getStatus() != null ? r.getStatus().name() : "",
                    r.getExpiresAt(),
                    remainingSec,
                    r.getIdempotencyKey(),
                    r.getReleaseReason(),
                    r.getCreatedAt()
            );
        }).toList();
    }

    @SuppressWarnings("unchecked")
    public List<OrderItemDto> getOrders(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        List<OrderEntity> list = entityManager.createQuery(
                "SELECT o FROM OrderEntity o ORDER BY o.createdAt DESC",
                OrderEntity.class
        ).setMaxResults(boundedLimit).getResultList();

        return list.stream().map(o -> new OrderItemDto(
                o.getOrderId(),
                o.getCustomerId(),
                o.getReservationId(),
                o.getStatus() != null ? o.getStatus().name() : "",
                o.getTotalAmount(),
                o.getCurrency(),
                o.getCancelReason(),
                o.getCreatedAt(),
                o.getUpdatedAt()
        )).toList();
    }

    @SuppressWarnings("unchecked")
    public List<PaymentItemDto> getPayments(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        List<PaymentEntity> list = entityManager.createQuery(
                "SELECT p FROM PaymentEntity p ORDER BY p.createdAt DESC",
                PaymentEntity.class
        ).setMaxResults(boundedLimit).getResultList();

        return list.stream().map(p -> new PaymentItemDto(
                p.getPaymentId(),
                p.getOrderId(),
                p.getReservationId(),
                p.getAmount(),
                p.getCurrency(),
                p.getStatus() != null ? p.getStatus().name() : "",
                p.getIdempotencyKey(),
                p.getGatewayRef(),
                p.getCreatedAt(),
                p.getUpdatedAt()
        )).toList();
    }

    @SuppressWarnings("unchecked")
    public List<EventLogItemDto> getEvents(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        List<OutboxEntity> list = entityManager.createQuery(
                "SELECT o FROM OutboxEntity o ORDER BY o.createdAt DESC",
                OutboxEntity.class
        ).setMaxResults(boundedLimit).getResultList();

        return list.stream().map(o -> new EventLogItemDto(
                o.getEventId(),
                o.getAggregateType(),
                o.getAggregateId(),
                o.getOrderId(),
                o.getType(),
                o.getVersion(),
                o.getPayload(),
                o.getCreatedAt(),
                o.getPublishedAt(),
                o.getPublishedAt() != null
        )).toList();
    }

    @SuppressWarnings("unchecked")
    public Optional<OrderTimelineResponse> getOrderTimeline(String orderId) {
        List<OrderEntity> orders = entityManager.createQuery(
                "SELECT o FROM OrderEntity o WHERE o.orderId = :orderId",
                OrderEntity.class
        ).setParameter("orderId", orderId).getResultList();

        if (orders.isEmpty()) {
            return Optional.empty();
        }
        OrderEntity order = orders.get(0);

        List<OrderStatusHistoryEntity> historyEntities = entityManager.createQuery(
                "SELECT h FROM OrderStatusHistoryEntity h WHERE h.order.orderId = :orderId ORDER BY h.changedAt ASC",
                OrderStatusHistoryEntity.class
        ).setParameter("orderId", orderId).getResultList();

        List<OrderTimelineResponse.StatusHistoryItemDto> history = historyEntities.stream().map(h ->
                new OrderTimelineResponse.StatusHistoryItemDto(
                        h.getHistoryId(),
                        orderId,
                        h.getOldStatus(),
                        h.getNewStatus(),
                        h.getReason(),
                        h.getEventId(),
                        h.getChangedAt()
                )
        ).toList();

        // Payment
        List<PaymentEntity> payments = entityManager.createQuery(
                "SELECT p FROM PaymentEntity p WHERE p.orderId = :orderId ORDER BY p.createdAt DESC",
                PaymentEntity.class
        ).setParameter("orderId", orderId).setMaxResults(1).getResultList();

        PaymentItemDto paymentDto = payments.isEmpty() ? null : new PaymentItemDto(
                payments.get(0).getPaymentId(),
                payments.get(0).getOrderId(),
                payments.get(0).getReservationId(),
                payments.get(0).getAmount(),
                payments.get(0).getCurrency(),
                payments.get(0).getStatus() != null ? payments.get(0).getStatus().name() : "",
                payments.get(0).getIdempotencyKey(),
                payments.get(0).getGatewayRef(),
                payments.get(0).getCreatedAt(),
                payments.get(0).getUpdatedAt()
        );

        // Reservation
        String resId = order.getReservationId();
        ReservationItemDto resDto = null;
        if (resId != null && !resId.isBlank()) {
            List<InventoryReservationEntity> resList = entityManager.createQuery(
                    "SELECT r FROM InventoryReservationEntity r WHERE r.reservationId = :resId",
                    InventoryReservationEntity.class
            ).setParameter("resId", resId).getResultList();

            if (!resList.isEmpty()) {
                InventoryReservationEntity r = resList.get(0);
                long remainingSec = r.getExpiresAt() != null ? Math.max(0, Duration.between(Instant.now(), r.getExpiresAt()).toSeconds()) : 0;
                resDto = new ReservationItemDto(
                        r.getReservationId(),
                        r.getProductId(),
                        r.getCustomerId(),
                        r.getOrderId(),
                        r.getQuantity(),
                        r.getStatus() != null ? r.getStatus().name() : "",
                        r.getExpiresAt(),
                        remainingSec,
                        r.getIdempotencyKey(),
                        r.getReleaseReason(),
                        r.getCreatedAt()
                );
            }
        }

        return Optional.of(new OrderTimelineResponse(
                order.getOrderId(),
                order.getCustomerId(),
                order.getStatus() != null ? order.getStatus().name() : "",
                order.getTotalAmount(),
                order.getCurrency(),
                order.getCancelReason(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                history,
                paymentDto,
                resDto
        ));
    }

    public SseEmitter subscribeStream() {
        SseEmitter emitter = new SseEmitter(0L); // Infinite timeout for long-lived UI stream
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        // Push immediate initial summary
        try {
            emitter.send(SseEmitter.event().name("summary").data(getSummary()));
        } catch (IOException e) {
            emitters.remove(emitter);
        }

        return emitter;
    }

    @Scheduled(fixedRate = 1000)
    public void pushPeriodicSummary() {
        if (emitters.isEmpty()) {
            return;
        }

        DashboardSummaryResponse summary = getSummary();
        List<SseEmitter> deadEmitters = new ArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("summary").data(summary));
            } catch (Exception e) {
                deadEmitters.add(emitter);
            }
        }

        emitters.removeAll(deadEmitters);
    }
}
