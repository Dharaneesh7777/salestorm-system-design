package com.salestorm.order.domain.state;

import com.salestorm.order.domain.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderStateTest {

    @Test
    @DisplayName("State Pattern: Happy path lifecycle progression from CREATED to DELIVERED")
    void testHappyPathLifecycleProgression() {
        OrderState state = new CreatedState();
        assertEquals(OrderStatus.CREATED, state.getStatus());

        state = state.initiatePayment();
        assertEquals(OrderStatus.PAYMENT_PENDING, state.getStatus());

        state = state.confirm();
        assertEquals(OrderStatus.CONFIRMED, state.getStatus());

        state = state.process();
        assertEquals(OrderStatus.PROCESSING, state.getStatus());

        state = state.ship();
        assertEquals(OrderStatus.SHIPPED, state.getStatus());

        state = state.outForDelivery();
        assertEquals(OrderStatus.OUT_FOR_DELIVERY, state.getStatus());

        state = state.deliver();
        assertEquals(OrderStatus.DELIVERED, state.getStatus());
    }

    @Test
    @DisplayName("State Pattern: No cancellation allowed after SHIPPED")
    void testNoCancelAfterShipped() {
        OrderState shipped = new ShippedState();
        assertThrows(IllegalStateException.class, () -> shipped.cancel("Customer changed mind"));

        OrderState outForDelivery = new OutForDeliveryState();
        assertThrows(IllegalStateException.class, () -> outForDelivery.cancel("Customer changed mind"));

        OrderState delivered = new DeliveredState();
        assertThrows(IllegalStateException.class, () -> delivered.cancel("Customer changed mind"));
    }

    @Test
    @DisplayName("State Pattern: Cancellation allowed before SHIPPED")
    void testCancelAllowedBeforeShipped() {
        OrderState created = new CreatedState();
        assertEquals(OrderStatus.CANCELLED, created.cancel("User cancel").getStatus());

        OrderState pending = new PaymentPendingState();
        assertEquals(OrderStatus.CANCELLED, pending.cancel("Payment timeout").getStatus());

        OrderState confirmed = new ConfirmedState();
        assertEquals(OrderStatus.CANCELLED, confirmed.cancel("Out of stock compensation").getStatus());

        OrderState processing = new ProcessingState();
        assertEquals(OrderStatus.CANCELLED, processing.cancel("Order error").getStatus());
    }

    @Test
    @DisplayName("State Pattern: Monotonic replays are ignored without exceptions")
    void testMonotonicReplaysIgnored() {
        OrderState confirmed = new ConfirmedState();

        // Replaying initiatePayment on confirmed order is ignored
        assertSame(confirmed, confirmed.initiatePayment());
        // Replaying confirm on confirmed order is ignored
        assertSame(confirmed, confirmed.confirm());
    }
}
