package com.salestorm.payment.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salestorm.common.api.GlobalExceptionHandler;
import com.salestorm.payment.api.dto.CreatePaymentRequest;
import com.salestorm.payment.application.PaymentService;
import com.salestorm.payment.application.dto.PaymentResult;
import com.salestorm.payment.domain.PaymentStatus;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentControllerTest {

    private MockMvc mockMvc;
    private PaymentService paymentService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        paymentService = Mockito.mock(PaymentService.class);
        objectMapper = new ObjectMapper();

        PaymentController controller = new PaymentController(paymentService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /api/v1/payments returns 200 OK on successful charge")
    void testProcessPaymentSuccess() throws Exception {
        PaymentResult mockResult = new PaymentResult(
                "pay_abc_123",
                "ord_9901",
                "res_555",
                new BigDecimal("99.99"),
                "USD",
                PaymentStatus.SUCCEEDED,
                "gw_ref_789",
                "IDEMP-KEY-100",
                Instant.now(),
                false
        );

        Mockito.when(paymentService.pay(eq("res_555"), eq("ord_9901"), any(BigDecimal.class), eq("USD"), eq("IDEMP-KEY-100")))
                .thenReturn(mockResult);

        CreatePaymentRequest request = new CreatePaymentRequest(
                "res_555",
                "ord_9901",
                new BigDecimal("99.99"),
                "USD"
        );

        mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "IDEMP-KEY-100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value("pay_abc_123"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.gatewayRef").value("gw_ref_789"));
    }

    @Test
    @DisplayName("POST /api/v1/payments returns 400 Bad Request when Idempotency-Key is missing")
    void testProcessPaymentMissingIdempotencyKey() throws Exception {
        CreatePaymentRequest request = new CreatePaymentRequest(
                "res_555",
                "ord_9901",
                new BigDecimal("99.99"),
                "USD"
        );

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/payments returns 503 Service Unavailable + Retry-After: 30 when Circuit Breaker is OPEN")
    void testProcessPaymentCircuitBreakerOpen() throws Exception {
        CircuitBreaker cb = CircuitBreaker.ofDefaults("paymentGateway");
        CallNotPermittedException openException = CallNotPermittedException.createCallNotPermittedException(cb);

        Mockito.when(paymentService.pay(any(), any(), any(), any(), anyString()))
                .thenThrow(openException);

        CreatePaymentRequest request = new CreatePaymentRequest(
                "res_555",
                "ord_9901",
                new BigDecimal("99.99"),
                "USD"
        );

        mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "IDEMP-KEY-CIRCUIT-OPEN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"));
    }
}
