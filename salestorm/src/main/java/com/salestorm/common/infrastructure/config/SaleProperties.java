package com.salestorm.common.infrastructure.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for flash sale parameters.
 */
@Validated
@ConfigurationProperties(prefix = "salestorm.sale")
public class SaleProperties {

    @NotNull
    @Min(1)
    private Integer totalStock = 100;

    @NotNull
    @Min(1)
    private Long reservationTtlSeconds = 300L;

    @NotNull
    @Min(1)
    private Long paymentGraceSeconds = 600L;

    private String pricingType = "FLAT";

    private java.math.BigDecimal baseUnitPrice = new java.math.BigDecimal("99.99");

    private Integer dealDiscountPercentage = 20;

    public Integer getTotalStock() {
        return totalStock;
    }

    public void setTotalStock(Integer totalStock) {
        this.totalStock = totalStock;
    }

    public Long getReservationTtlSeconds() {
        return reservationTtlSeconds;
    }

    public void setReservationTtlSeconds(Long reservationTtlSeconds) {
        this.reservationTtlSeconds = reservationTtlSeconds;
    }

    public Long getPaymentGraceSeconds() {
        return paymentGraceSeconds;
    }

    public void setPaymentGraceSeconds(Long paymentGraceSeconds) {
        this.paymentGraceSeconds = paymentGraceSeconds;
    }

    public String getPricingType() {
        return pricingType;
    }

    public void setPricingType(String pricingType) {
        this.pricingType = pricingType;
    }

    public java.math.BigDecimal getBaseUnitPrice() {
        return baseUnitPrice;
    }

    public void setBaseUnitPrice(java.math.BigDecimal baseUnitPrice) {
        this.baseUnitPrice = baseUnitPrice;
    }

    public Integer getDealDiscountPercentage() {
        return dealDiscountPercentage;
    }

    public void setDealDiscountPercentage(Integer dealDiscountPercentage) {
        this.dealDiscountPercentage = dealDiscountPercentage;
    }
}
