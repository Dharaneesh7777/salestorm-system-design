package com.salestorm.checkout.infrastructure.pricing;

import com.salestorm.checkout.domain.pricing.PricingStrategy;
import com.salestorm.checkout.domain.pricing.PricingStrategySelector;
import com.salestorm.checkout.domain.pricing.PricingType;
import com.salestorm.common.infrastructure.config.SaleProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class DefaultPricingStrategySelector implements PricingStrategySelector {

    private final SaleProperties saleProperties;
    private final Map<PricingType, PricingStrategy> strategies;

    public DefaultPricingStrategySelector(SaleProperties saleProperties, List<PricingStrategy> strategyList) {
        this.saleProperties = saleProperties;
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(PricingStrategy::getPricingType, Function.identity()));
    }

    @Override
    public PricingStrategy select() {
        String configuredType = saleProperties.getPricingType();
        PricingType type = PricingType.FLAT;
        if (configuredType != null && !configuredType.isBlank()) {
            try {
                type = PricingType.valueOf(configuredType.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                type = PricingType.FLAT;
            }
        }
        return select(type);
    }

    @Override
    public PricingStrategy select(PricingType pricingType) {
        PricingStrategy strategy = strategies.get(pricingType);
        if (strategy == null) {
            strategy = strategies.get(PricingType.FLAT);
        }
        return strategy;
    }
}
