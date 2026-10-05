package com.salestorm.checkout.infrastructure.saga;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salestorm.checkout.application.CheckoutSaga;
import com.salestorm.common.domain.event.EventBus;
import com.salestorm.common.domain.event.MessageEnvelope;
import com.salestorm.common.domain.event.ProcessedEventRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class CheckoutSagaListener {

    private static final Logger log = LoggerFactory.getLogger(CheckoutSagaListener.class);
    private static final String CONSUMER_NAME = "CheckoutSagaListener";

    private final EventBus eventBus;
    private final ProcessedEventRepository processedEventRepository;
    private final CheckoutSaga checkoutSaga;
    private final ObjectMapper objectMapper;

    public CheckoutSagaListener(
            EventBus eventBus,
            ProcessedEventRepository processedEventRepository,
            CheckoutSaga checkoutSaga,
            ObjectMapper objectMapper
    ) {
        this.eventBus = eventBus;
        this.processedEventRepository = processedEventRepository;
        this.checkoutSaga = checkoutSaga;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void register() {
        eventBus.subscribe("PaymentInitiated", this::handlePaymentInitiated);
        eventBus.subscribe("PaymentSucceeded", this::handlePaymentSucceeded);
        eventBus.subscribe("PaymentFailed", this::handlePaymentFailed);
        eventBus.subscribe("OrderConfirmed", this::handleOrderConfirmed);
        eventBus.subscribe("ReservationConfirmationRejected", this::handleReservationConfirmationRejected);
        eventBus.subscribe("CheckoutFailed", this::handleCheckoutFailed);
    }

    public void handlePaymentInitiated(MessageEnvelope envelope) {
        if (!processedEventRepository.markIfNew(envelope.eventId(), CONSUMER_NAME + "_PayInit")) {
            return;
        }
        Map<String, Object> payload = parsePayload(envelope.payload());
        String orderId = (String) payload.getOrDefault("orderId", envelope.orderId());
        String paymentId = (String) payload.get("paymentId");
        if (orderId != null) {
            checkoutSaga.onPaymentInitiated(orderId, paymentId, envelope.eventId());
        }
    }

    public void handlePaymentSucceeded(MessageEnvelope envelope) {
        if (!processedEventRepository.markIfNew(envelope.eventId(), CONSUMER_NAME + "_PaySuccess")) {
            return;
        }
        Map<String, Object> payload = parsePayload(envelope.payload());
        String orderId = (String) payload.getOrDefault("orderId", envelope.orderId());
        String paymentId = (String) payload.get("paymentId");
        String reservationId = (String) payload.get("reservationId");
        String amountStr = (String) payload.get("amount");
        BigDecimal amount = amountStr != null && !amountStr.isBlank() ? new BigDecimal(amountStr) : null;

        if (orderId != null) {
            checkoutSaga.onPaymentSucceeded(orderId, paymentId, reservationId, amount, envelope.eventId());
        }
    }

    public void handlePaymentFailed(MessageEnvelope envelope) {
        if (!processedEventRepository.markIfNew(envelope.eventId(), CONSUMER_NAME + "_PayFail")) {
            return;
        }
        Map<String, Object> payload = parsePayload(envelope.payload());
        String orderId = (String) payload.getOrDefault("orderId", envelope.orderId());
        String reason = (String) payload.getOrDefault("reason", "Payment Declined or Timed Out");

        if (orderId != null) {
            checkoutSaga.onPaymentFailed(orderId, reason, envelope.eventId());
        }
    }

    public void handleOrderConfirmed(MessageEnvelope envelope) {
        if (!processedEventRepository.markIfNew(envelope.eventId(), CONSUMER_NAME + "_OrderConf")) {
            return;
        }
        Map<String, Object> payload = parsePayload(envelope.payload());
        String orderId = (String) payload.getOrDefault("orderId", envelope.orderId());
        if (orderId != null) {
            checkoutSaga.onOrderConfirmed(orderId, envelope.eventId());
        }
    }

    public void handleReservationConfirmationRejected(MessageEnvelope envelope) {
        if (!processedEventRepository.markIfNew(envelope.eventId(), CONSUMER_NAME + "_ResRej")) {
            return;
        }
        Map<String, Object> payload = parsePayload(envelope.payload());
        String orderId = (String) payload.getOrDefault("orderId", envelope.orderId());
        String reservationId = (String) payload.get("reservationId");
        String paymentId = (String) payload.get("paymentId");
        String reason = (String) payload.getOrDefault("reason", "Reservation expired");

        if (orderId != null) {
            checkoutSaga.onReservationConfirmationRejected(orderId, reservationId, paymentId, reason);
        }
    }

    public void handleCheckoutFailed(MessageEnvelope envelope) {
        if (!processedEventRepository.markIfNew(envelope.eventId(), CONSUMER_NAME + "_ChkFail")) {
            return;
        }
        Map<String, Object> payload = parsePayload(envelope.payload());
        String orderId = (String) payload.getOrDefault("orderId", envelope.orderId());
        String reservationId = (String) payload.get("reservationId");
        String paymentId = (String) payload.get("paymentId");
        String reason = (String) payload.getOrDefault("reason", "Checkout pipeline failure");

        if (orderId != null) {
            checkoutSaga.onCheckoutFailed(orderId, reservationId, paymentId, reason);
        }
    }

    private Map<String, Object> parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse event payload JSON in saga listener: {}", payloadJson);
            return Map.of();
        }
    }
}
