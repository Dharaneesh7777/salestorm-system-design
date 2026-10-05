package com.salestorm.inventory.infrastructure.persistence;

import com.salestorm.inventory.domain.InventoryRepository;
import com.salestorm.inventory.infrastructure.entity.InventoryEntity;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class InventoryRepositoryImpl implements InventoryRepository {

    private final SpringDataJpaInventoryRepository springDataRepository;

    public InventoryRepositoryImpl(SpringDataJpaInventoryRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    public int reserveOne(Long productId) {
        return springDataRepository.reserveOne(productId);
    }

    @Override
    public int releaseOne(Long productId) {
        return springDataRepository.releaseOne(productId);
    }

    @Override
    public int sellOne(Long productId) {
        return springDataRepository.sellOne(productId);
    }

    @Override
    public Optional<InventoryEntity> findByProductId(Long productId) {
        return springDataRepository.findByProductId(productId);
    }

    @Override
    public java.util.List<InventoryEntity> findAll() {
        return springDataRepository.findAll();
    }

    @Override
    public InventoryEntity save(InventoryEntity inventory) {
        return springDataRepository.save(inventory);
    }
}
