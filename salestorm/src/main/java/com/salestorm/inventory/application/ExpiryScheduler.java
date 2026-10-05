package com.salestorm.inventory.application;

import com.salestorm.inventory.domain.ReleaseReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Background scheduler scanning for expired reservations and invoking release operations.
 * Strictly delegates domain mutations and transactions to ReservationService.
 */
@Component
public class ExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiryScheduler.class);
    private static final int BATCH_SIZE = 50;

    private final ReservationService reservationService;

    public ExpiryScheduler(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @Scheduled(fixedDelay = 2000)
    public void processExpiredReservations() {
        List<String> expiredIds = reservationService.findExpiredReservationIds(BATCH_SIZE);
        if (!expiredIds.isEmpty()) {
            log.debug("Found {} expired reservations to process", expiredIds.size());
        }

        for (String reservationId : expiredIds) {
            try {
                reservationService.release(reservationId, ReleaseReason.EXPIRED);
            } catch (Exception e) {
                log.error("Failed to release expired reservation {}", reservationId, e);
            }
        }
    }
}
