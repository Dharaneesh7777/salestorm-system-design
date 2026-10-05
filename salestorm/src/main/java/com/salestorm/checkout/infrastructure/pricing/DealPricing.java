package com.salestorm.checkout.infrastructure.pricing;

import com.salestorm.checkout.domain.pricing.PricingStrategy;
import com.salestorm.checkout.domain.pricing.PricingType;
import com.salestorm.common.domain.Money;
import com.salestorm.common.infrastructure.config.SaleProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class DealPricing implements PricingStrategy {

    private final SaleProperties saleProperties;

    public DealPricing(SaleProperties saleProperties) {
        this.saleProperties = saleProperties;
    }

    @Override
    public PricingType getPricingType() {
        return PricingType.DEAL;
    }

    @Override
    public Money calculatePrice(Long productId, Long customerId, int quantity, String couponCode) {
        BigDecimal unitPrice = saleProperties.getBaseUnitPrice() != null ? saleProperties.getBaseUnitPrice() : new BigDecimal("99.99");
        int discountPercent = saleProperties.getDealDiscountPercentage() != null ? saleProperties.getDealDiscountPercentage() : 20;
        BigDecimal multiplier = BigDecimal.valueOf(100 - discountPercent)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        BigDecimal discountedUnitPrice = unitPrice.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
        return Money.of(discountedUnitPrice.multiply(BigDecimal.valueOf(quantity)));
    }
}
