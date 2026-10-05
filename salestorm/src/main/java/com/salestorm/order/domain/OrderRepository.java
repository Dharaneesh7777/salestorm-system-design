package com.salestorm.order.domain;

import com.salestorm.order.infrastructure.entity.OrderEntity;

import java.util.List;
import java.util.Optional;

/**
 * Domain repository port for Order entities.
 */
public interface OrderRepository {

    OrderEntity save(OrderEntity order);

    Optional<OrderEntity> findById(String orderId);

    Optional<OrderEntity> findByReservationId(String reservationId);

    List<OrderEntity> findByCustomerId(Long customerId);

    List<OrderEntity> findByStatus(OrderStatus status);
}
