package com.salestorm.checkout.domain.pricing;

public interface PricingStrategySelector {

    PricingStrategy select();

    PricingStrategy select(PricingType pricingType);
}
