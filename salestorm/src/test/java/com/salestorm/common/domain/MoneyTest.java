package com.salestorm.common.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {

    @Test
    void testMoneyCreationAndArithmetic() {
        Money m1 = Money.of(100.50, "USD");
        Money m2 = Money.of(49.50, "USD");

        Money sum = m1.add(m2);
        assertEquals(new BigDecimal("150.00"), sum.getAmount());
        assertEquals(Currency.getInstance("USD"), sum.getCurrency());

        Money diff = m1.subtract(m2);
        assertEquals(new BigDecimal("51.00"), diff.getAmount());

        Money multiplied = m2.multiply(2);
        assertEquals(new BigDecimal("99.00"), multiplied.getAmount());
    }

    @Test
    void testDifferentCurrenciesThrowException() {
        Money usd = Money.of(10.0, "USD");
        Money eur = Money.of(10.0, "EUR");

        assertThrows(IllegalArgumentException.class, () -> usd.add(eur));
        assertThrows(IllegalArgumentException.class, () -> usd.compareTo(eur));
    }

    @Test
    void testComparisons() {
        Money smaller = Money.of(50.0, "USD");
        Money larger = Money.of(100.0, "USD");

        assertTrue(smaller.isLessThan(larger));
        assertTrue(larger.isGreaterThan(smaller));
        assertFalse(smaller.isGreaterThanOrEqual(larger));
        assertTrue(smaller.isPositive());
    }
}
