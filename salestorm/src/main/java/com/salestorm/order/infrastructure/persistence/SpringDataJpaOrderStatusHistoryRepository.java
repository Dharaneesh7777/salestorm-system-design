package com.salestorm.order.infrastructure.persistence;

import com.salestorm.order.infrastructure.entity.OrderStatusHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataJpaOrderStatusHistoryRepository extends JpaRepository<OrderStatusHistoryEntity, Long> {

    @Query("SELECT h FROM OrderStatusHistoryEntity h WHERE h.order.orderId = :orderId ORDER BY h.changedAt ASC")
    List<OrderStatusHistoryEntity> findByOrderId(@Param("orderId") String orderId);

    @Query("SELECT h FROM OrderStatusHistoryEntity h WHERE h.order.orderId = :orderId AND h.eventId = :eventId")
    Optional<OrderStatusHistoryEntity> findByOrderIdAndEventId(@Param("orderId") String orderId, @Param("eventId") String eventId);
}
