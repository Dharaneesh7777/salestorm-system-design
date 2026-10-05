package com.salestorm.checkout.infrastructure.pricing;

import com.salestorm.checkout.domain.pricing.PricingStrategy;
import com.salestorm.checkout.domain.pricing.PricingType;
import com.salestorm.common.domain.Money;
import com.salestorm.common.infrastructure.config.SaleProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class FlatPricing implements PricingStrategy {

    private final SaleProperties saleProperties;

    public FlatPricing(SaleProperties saleProperties) {
        this.saleProperties = saleProperties;
    }

    @Override
    public PricingType getPricingType() {
        return PricingType.FLAT;
    }

    @Override
    public Money calculatePrice(Long productId, Long customerId, int quantity, String couponCode) {
        BigDecimal unitPrice = saleProperties.getBaseUnitPrice() != null ? saleProperties.getBaseUnitPrice() : new BigDecimal("99.99");
        return Money.of(unitPrice.multiply(BigDecimal.valueOf(quantity)));
    }
}
