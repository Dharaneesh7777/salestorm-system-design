package com.salestorm.order.domain;

import com.salestorm.order.infrastructure.entity.OrderStatusHistoryEntity;

import java.util.List;
import java.util.Optional;

/**
 * Domain repository port for Order status history audit logs.
 */
public interface OrderStatusHistoryRepository {

    OrderStatusHistoryEntity save(OrderStatusHistoryEntity history);

    List<OrderStatusHistoryEntity> findByOrderId(String orderId);

    Optional<OrderStatusHistoryEntity> findByOrderIdAndEventId(String orderId, String eventId);
}
