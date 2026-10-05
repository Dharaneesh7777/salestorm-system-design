package com.salestorm.checkout.infrastructure.pricing;

import com.salestorm.checkout.domain.pricing.PricingStrategy;
import com.salestorm.checkout.domain.pricing.PricingType;
import com.salestorm.common.domain.Money;
import com.salestorm.common.infrastructure.config.SaleProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class CouponPricing implements PricingStrategy {

    private final SaleProperties saleProperties;

    public CouponPricing(SaleProperties saleProperties) {
        this.saleProperties = saleProperties;
    }

    @Override
    public PricingType getPricingType() {
        return PricingType.COUPON;
    }

    @Override
    public Money calculatePrice(Long productId, Long customerId, int quantity, String couponCode) {
        BigDecimal unitPrice = saleProperties.getBaseUnitPrice() != null ? saleProperties.getBaseUnitPrice() : new BigDecimal("99.99");
        BigDecimal rawTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));

        if (couponCode != null && !couponCode.isBlank()) {
            String code = couponCode.trim().toUpperCase();
            if ("SAVE10".equals(code) || "DISCOUNT10".equals(code)) {
                rawTotal = rawTotal.multiply(new BigDecimal("0.90")).setScale(2, RoundingMode.HALF_UP);
            } else if ("SAVE20".equals(code)) {
                rawTotal = rawTotal.multiply(new BigDecimal("0.80")).setScale(2, RoundingMode.HALF_UP);
            } else if ("FLAT5".equals(code)) {
                rawTotal = rawTotal.subtract(new BigDecimal("5.00")).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            }
        }
        return Money.of(rawTotal);
    }
}
