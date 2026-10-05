package com.salestorm.payment.infrastructure.resilience;

import com.salestorm.payment.domain.PaymentGateway;
import com.salestorm.payment.infrastructure.adapter.MockGatewayAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Factory and Spring configuration providing the resilient payment gateway bean.
 */
@Configuration
public class PaymentGatewayFactory {

    @Bean
    @Primary
    public PaymentGateway paymentGateway(MockGatewayAdapter mockGatewayAdapter) {
        return createResilientGateway(mockGatewayAdapter);
    }

    /**
     * Factory method for creating resilient gateway wrappers in unit and contract tests.
     */
    public static PaymentGateway createResilientGateway(PaymentGateway delegate) {
        return new ResilientPaymentGateway(
                delegate,
                ResilientPaymentGateway.createDefaultCircuitBreaker(),
                ResilientPaymentGateway.createDefaultRetry(),
                ResilientPaymentGateway.createDefaultBulkhead(),
                ResilientPaymentGateway.createDefaultTimeLimiter()
        );
    }
}
