package com.salestorm.checkout.domain.pricing;

import com.salestorm.checkout.infrastructure.pricing.CouponPricing;
import com.salestorm.checkout.infrastructure.pricing.DealPricing;
import com.salestorm.checkout.infrastructure.pricing.DefaultPricingStrategySelector;
import com.salestorm.checkout.infrastructure.pricing.FlatPricing;
import com.salestorm.common.domain.Money;
import com.salestorm.common.infrastructure.config.SaleProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PricingStrategyTest {

    private SaleProperties saleProperties;
    private FlatPricing flatPricing;
    private DealPricing dealPricing;
    private CouponPricing couponPricing;
    private DefaultPricingStrategySelector selector;

    @BeforeEach
    void setUp() {
        saleProperties = new SaleProperties();
        saleProperties.setBaseUnitPrice(new BigDecimal("100.00"));
        saleProperties.setDealDiscountPercentage(20); // 20% off -> $80.00
        saleProperties.setPricingType("FLAT");

        flatPricing = new FlatPricing(saleProperties);
        dealPricing = new DealPricing(saleProperties);
        couponPricing = new CouponPricing(saleProperties);

        selector = new DefaultPricingStrategySelector(
                saleProperties,
                List.of(flatPricing, dealPricing, couponPricing)
        );
    }

    @Test
    @DisplayName("Flat pricing calculates baseUnitPrice * quantity without discounts")
    void testFlatPricing() {
        Money price1 = flatPricing.calculatePrice(1L, 1001L, 1);
        assertEquals(new BigDecimal("100.00"), price1.getAmount());

        Money price2 = flatPricing.calculatePrice(1L, 1001L, 2);
        assertEquals(new BigDecimal("200.00"), price2.getAmount());
    }

    @Test
    @DisplayName("Deal pricing applies configured percentage discount during flash sale")
    void testDealPricing() {
        Money dealPrice = dealPricing.calculatePrice(1L, 1001L, 1);
        assertEquals(new BigDecimal("80.00"), dealPrice.getAmount());

        Money dealPrice2 = dealPricing.calculatePrice(1L, 1001L, 2);
        assertEquals(new BigDecimal("160.00"), dealPrice2.getAmount());
    }

    @Test
    @DisplayName("Coupon pricing applies specific coupon discounts or falls back to base price")
    void testCouponPricing() {
        // Valid coupon SAVE10 -> 10% off
        Money priceSave10 = couponPricing.calculatePrice(1L, 1001L, 1, "SAVE10");
        assertEquals(new BigDecimal("90.00"), priceSave10.getAmount());

        // Valid coupon SAVE20 -> 20% off
        Money priceSave20 = couponPricing.calculatePrice(1L, 1001L, 1, "SAVE20");
        assertEquals(new BigDecimal("80.00"), priceSave20.getAmount());

        // Valid coupon FLAT5 -> $5.00 off
        Money priceFlat5 = couponPricing.calculatePrice(1L, 1001L, 1, "FLAT5");
        assertEquals(new BigDecimal("95.00"), priceFlat5.getAmount());

        // Invalid coupon -> full base price
        Money priceInvalid = couponPricing.calculatePrice(1L, 1001L, 1, "INVALID_CODE");
        assertEquals(new BigDecimal("100.00"), priceInvalid.getAmount());
    }

    @Test
    @DisplayName("PricingStrategySelector picks strategy from config")
    void testSelector() {
        saleProperties.setPricingType("DEAL");
        assertEquals(PricingType.DEAL, selector.select().getPricingType());

        saleProperties.setPricingType("COUPON");
        assertEquals(PricingType.COUPON, selector.select().getPricingType());

        saleProperties.setPricingType("UNKNOWN");
        assertEquals(PricingType.FLAT, selector.select().getPricingType());
    }
}
