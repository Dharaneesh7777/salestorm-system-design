package com.salestorm.order.infrastructure.persistence;

import com.salestorm.order.domain.OrderRepository;
import com.salestorm.order.domain.OrderStatus;
import com.salestorm.order.infrastructure.entity.OrderEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class OrderRepositoryImpl implements OrderRepository {

    private final SpringDataJpaOrderRepository springDataRepository;

    public OrderRepositoryImpl(SpringDataJpaOrderRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    public OrderEntity save(OrderEntity order) {
        return springDataRepository.save(order);
    }

    @Override
    public Optional<OrderEntity> findById(String orderId) {
        return springDataRepository.findById(orderId);
    }

    @Override
    public Optional<OrderEntity> findByReservationId(String reservationId) {
        return springDataRepository.findByReservationId(reservationId);
    }

    @Override
    public List<OrderEntity> findByCustomerId(Long customerId) {
        return springDataRepository.findByCustomerId(customerId);
    }

    @Override
    public List<OrderEntity> findByStatus(OrderStatus status) {
        return springDataRepository.findByStatus(status);
    }
}
