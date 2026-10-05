package com.salestorm.checkout.domain.pricing;

import com.salestorm.common.domain.Money;

public interface PricingStrategy {

    PricingType getPricingType();

    Money calculatePrice(Long productId, Long customerId, int quantity, String couponCode);

    default Money calculatePrice(Long productId, Long customerId, int quantity) {
        return calculatePrice(productId, customerId, quantity, null);
    }
}
