package com.salestorm.checkout.application;

import com.salestorm.checkout.application.dto.CheckoutBuyResult;
import com.salestorm.checkout.application.dto.CheckoutPayResult;
import com.salestorm.checkout.application.dto.CheckoutStatusResult;
import com.salestorm.checkout.domain.pricing.PricingStrategy;
import com.salestorm.checkout.domain.pricing.PricingStrategySelector;
import com.salestorm.checkout.domain.saga.SagaStateEntity;
import com.salestorm.checkout.domain.saga.SagaStateRepository;
import com.salestorm.common.domain.Money;
import com.salestorm.inventory.application.ReservationService;
import com.salestorm.inventory.application.dto.ReservationResult;
import com.salestorm.order.application.OrderService;
import com.salestorm.order.application.dto.CreateOrderCommand;
import com.salestorm.order.application.dto.OrderItemDto;
import com.salestorm.order.application.dto.OrderResult;
import com.salestorm.payment.application.PaymentService;
import com.salestorm.payment.application.dto.PaymentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Facade pattern implementation orchestrating Flash Sale Checkout.
 * Only sequences calls across Inventory, Pricing, Order, Payment, and Saga components;
 * all business rules reside in their respective domain services.
 */
@Service
public class CheckoutFacade {

    private static final Logger log = LoggerFactory.getLogger(CheckoutFacade.class);

    private final PricingStrategySelector pricingStrategySelector;
    private final ReservationService reservationService;
    private final OrderService orderService;
    private final PaymentService paymentService;
    private final CheckoutSaga checkoutSaga;
    private final SagaStateRepository sagaRepository;

    public CheckoutFacade(
            PricingStrategySelector pricingStrategySelector,
            ReservationService reservationService,
            OrderService orderService,
            PaymentService paymentService,
            CheckoutSaga checkoutSaga,
            SagaStateRepository sagaRepository
    ) {
        this.pricingStrategySelector = pricingStrategySelector;
        this.reservationService = reservationService;
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.checkoutSaga = checkoutSaga;
        this.sagaRepository = sagaRepository;
    }

    /**
     * Entry point for 'Buy Now'. Sequences pricing, stock reservation, saga initialization, and order creation.
     */
    public CheckoutBuyResult buy(Long customerId, Long productId, String idempotencyKey) {
        return buy(customerId, productId, 1, null, idempotencyKey);
    }

    public CheckoutBuyResult buy(Long customerId, Long productId, int quantity, String couponCode, String idempotencyKey) {
        int effectiveQuantity = quantity > 0 ? quantity : 1;
        log.info("CHECKOUT BUY: customerId={}, productId={}, quantity={}, idempotencyKey={}",
                customerId, productId, effectiveQuantity, idempotencyKey);

        // 1. Calculate price once using active PricingStrategy
        PricingStrategy pricingStrategy = pricingStrategySelector.select();
        Money totalPriced = pricingStrategy.calculatePrice(productId, customerId, effectiveQuantity, couponCode);
        BigDecimal unitPrice = totalPriced.getAmount().divide(BigDecimal.valueOf(effectiveQuantity), 2, RoundingMode.HALF_UP);

        // 2. Reserve stock in Inventory
        ReservationResult reservation = reservationService.reserve(customerId, productId, idempotencyKey);

        // 3. Generate orderId and start CheckoutSaga
        String orderId = UUID.randomUUID().toString();
        String currencyCode = totalPriced.getCurrency().getCurrencyCode();
        SagaStateEntity saga = checkoutSaga.startSaga(
                orderId,
                reservation.reservationId(),
                customerId,
                productId,
                effectiveQuantity,
                totalPriced.getAmount(),
                currencyCode
        );

        // 4. Create initial Order in CREATED status
        OrderItemDto itemDto = new OrderItemDto(productId, effectiveQuantity, unitPrice, totalPriced.getAmount());
        CreateOrderCommand orderCmd = new CreateOrderCommand(
                orderId,
                customerId,
                reservation.reservationId(),
                totalPriced.getAmount(),
                currencyCode,
                List.of(itemDto)
        );
        orderService.onCreateOrder(orderCmd, null);

        return new CheckoutBuyResult(
                orderId,
                reservation.reservationId(),
                customerId,
                productId,
                effectiveQuantity,
                totalPriced.getAmount(),
                currencyCode,
                reservation.expiresAt(),
                saga.getStatus().name()
        );
    }

    /**
     * Entry point for payment execution. Validates amount against priced total and invokes payment processing.
     */
    public CheckoutPayResult pay(Long customerId, String orderId, BigDecimal amount, String idempotencyKey) {
        log.info("CHECKOUT PAY: customerId={}, orderId={}, amount={}, idempotencyKey={}",
                customerId, orderId, amount, idempotencyKey);

        // 1. Retrieve saga state
        SagaStateEntity saga = sagaRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Saga/Order not found for orderId: " + orderId));

        // 2. Validate amount matches priced total
        if (amount != null && amount.compareTo(saga.getAmount()) != 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    String.format("Payment amount %s does not match priced total %s", amount, saga.getAmount())
            );
        }

        BigDecimal effectiveAmount = amount != null ? amount : saga.getAmount();

        // 3. Execute payment via PaymentService
        PaymentResult paymentResult = paymentService.pay(
                saga.getReservationId(),
                orderId,
                effectiveAmount,
                saga.getCurrency(),
                idempotencyKey
        );

        String failureReason = paymentResult.status() == com.salestorm.payment.domain.PaymentStatus.FAILED ? "Payment declined or failed" : null;

        return new CheckoutPayResult(
                orderId,
                paymentResult.paymentId(),
                paymentResult.status().name(),
                paymentResult.amount(),
                paymentResult.currency(),
                paymentResult.gatewayRef(),
                failureReason
        );
    }

    /**
     * Reads from Checkout Saga record so status is available even during downstream service outages.
     */
    public CheckoutStatusResult getStatus(String orderId) {
        Optional<SagaStateEntity> sagaOpt = sagaRepository.findByOrderId(orderId);
        if (sagaOpt.isPresent()) {
            SagaStateEntity saga = sagaOpt.get();
            String orderStatus = null;
            try {
                Optional<OrderResult> orderOpt = orderService.getOrderById(orderId);
                if (orderOpt.isPresent()) {
                    orderStatus = orderOpt.get().status().name();
                }
            } catch (Exception ignored) {
            }

            return new CheckoutStatusResult(
                    orderId,
                    saga.getStatus().name(),
                    orderStatus != null ? orderStatus : saga.getStatus().name(),
                    saga.getPaymentId() != null ? "RECORDED" : "UNPAID",
                    saga.getReservationId(),
                    saga.getAmount(),
                    saga.getCurrency(),
                    saga.getFailureReason(),
                    saga.getUpdatedAt()
            );
        }

        // Fallback to OrderService
        OrderResult order = orderService.getOrderById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found for orderId: " + orderId));

        return new CheckoutStatusResult(
                orderId,
                order.status().name(),
                order.status().name(),
                null,
                order.reservationId(),
                order.totalAmount(),
                order.currency(),
                order.cancelReason(),
                order.createdAt()
        );
    }
}
