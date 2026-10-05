package com.salestorm.order.infrastructure.listener;

import com.salestorm.common.domain.event.EventBus;
import com.salestorm.common.domain.event.MessageEnvelope;
import com.salestorm.common.domain.event.ProcessedEventRepository;
import com.salestorm.order.application.OrderService;
import com.salestorm.order.domain.OrderStatus;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Observer shipment listener triggering fulfillment upon order confirmation.
 * Leverages the PROCESSED_EVENT inbox to skip duplicate event executions.
 */
@Component
public class ShipmentListener {

    private static final Logger log = LoggerFactory.getLogger(ShipmentListener.class);
    private static final String CONSUMER_NAME = "ShipmentListener";

    private final EventBus eventBus;
    private final ProcessedEventRepository processedEventRepository;
    private final OrderService orderService;

    public ShipmentListener(
            EventBus eventBus,
            ProcessedEventRepository processedEventRepository,
            OrderService orderService
    ) {
        this.eventBus = eventBus;
        this.processedEventRepository = processedEventRepository;
        this.orderService = orderService;
    }

    @PostConstruct
    public void register() {
        eventBus.subscribe("OrderConfirmed", this::handleOrderConfirmed);
    }

    public void handleOrderConfirmed(MessageEnvelope envelope) {
        if (!processedEventRepository.markIfNew(envelope.eventId(), CONSUMER_NAME)) {
            log.debug("ShipmentListener skipping duplicate event: {}", envelope.eventId());
            return;
        }

        String orderId = envelope.orderId();
        log.info("SHIPMENT: Initializing shipment pipeline for confirmed order {}", orderId);

        try {
            orderService.updateOrderStatus(orderId, OrderStatus.PROCESSING, "Warehouse fulfillment started", envelope.eventId());
            log.info("SHIPMENT: Created mock shipment package for order {}. Carrier: SaleStorm Express.", orderId);
        } catch (Exception e) {
            log.error("Error processing shipment for order {}: {}", orderId, e.getMessage(), e);
        }
    }
}
