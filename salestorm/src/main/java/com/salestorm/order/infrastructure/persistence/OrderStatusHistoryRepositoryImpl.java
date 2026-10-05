package com.salestorm.order.infrastructure.persistence;

import com.salestorm.order.domain.OrderStatusHistoryRepository;
import com.salestorm.order.infrastructure.entity.OrderStatusHistoryEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class OrderStatusHistoryRepositoryImpl implements OrderStatusHistoryRepository {

    private final SpringDataJpaOrderStatusHistoryRepository springDataRepository;

    public OrderStatusHistoryRepositoryImpl(SpringDataJpaOrderStatusHistoryRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    public OrderStatusHistoryEntity save(OrderStatusHistoryEntity history) {
        return springDataRepository.save(history);
    }

    @Override
    public List<OrderStatusHistoryEntity> findByOrderId(String orderId) {
        return springDataRepository.findByOrderId(orderId);
    }

    @Override
    public Optional<OrderStatusHistoryEntity> findByOrderIdAndEventId(String orderId, String eventId) {
        return springDataRepository.findByOrderIdAndEventId(orderId, eventId);
    }
}
