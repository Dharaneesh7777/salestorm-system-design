package com.salestorm.order.infrastructure.persistence;

import com.salestorm.order.domain.OrderStatus;
import com.salestorm.order.infrastructure.entity.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataJpaOrderRepository extends JpaRepository<OrderEntity, String> {

    Optional<OrderEntity> findByReservationId(String reservationId);

    List<OrderEntity> findByCustomerId(Long customerId);

    List<OrderEntity> findByStatus(OrderStatus status);
}
