package com.salestorm.checkout.infrastructure.saga;

import com.salestorm.checkout.application.CheckoutSaga;
import com.salestorm.checkout.domain.saga.SagaStateEntity;
import com.salestorm.checkout.domain.saga.SagaStateRepository;
import com.salestorm.checkout.domain.saga.SagaStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Watchdog scheduler checking for sagas stuck in PAID state without OrderConfirmed acknowledgment.
 * After threshold (default 60 seconds), re-emits idempotent ConfirmOrder commands.
 */
@Component
public class SagaWatchdog {

    private static final Logger log = LoggerFactory.getLogger(SagaWatchdog.class);

    private final SagaStateRepository sagaRepository;
    private final CheckoutSaga checkoutSaga;
    private final long stuckThresholdSeconds;

    public SagaWatchdog(
            SagaStateRepository sagaRepository,
            CheckoutSaga checkoutSaga,
            @Value("${salestorm.checkout.watchdog.stuck-threshold-seconds:60}") long stuckThresholdSeconds
    ) {
        this.sagaRepository = sagaRepository;
        this.checkoutSaga = checkoutSaga;
        this.stuckThresholdSeconds = stuckThresholdSeconds;
    }

    @Scheduled(fixedDelayString = "${salestorm.checkout.watchdog.interval-ms:5000}")
    public void scanAndRecoverStuckSagas() {
        recoverStuckSagas();
    }

    public int recoverStuckSagas() {
        Instant cutoff = Instant.now().minusSeconds(stuckThresholdSeconds);
        List<SagaStateEntity> stuckSagas = sagaRepository.findStuckSagas(SagaStatus.PAID, cutoff, 50);

        int count = 0;
        for (SagaStateEntity saga : stuckSagas) {
            log.warn("WATCHDOG: Found stuck PAID saga for order {} (idle since {}). Re-emitting ConfirmOrder command.",
                    saga.getOrderId(), saga.getUpdatedAt());

            checkoutSaga.sendConfirmOrderCommand(
                    saga.getOrderId(),
                    saga.getPaymentId(),
                    saga.getReservationId(),
                    saga.getAmount()
            );
            count++;
        }
        return count;
    }
}
