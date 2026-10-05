package com.salestorm.checkout.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salestorm.checkout.api.dto.CheckoutBuyRequest;
import com.salestorm.checkout.api.dto.CheckoutPayRequest;
import com.salestorm.checkout.application.CheckoutFacade;
import com.salestorm.checkout.application.dto.CheckoutBuyResult;
import com.salestorm.checkout.application.dto.CheckoutPayResult;
import com.salestorm.checkout.application.dto.CheckoutStatusResult;
import com.salestorm.checkout.infrastructure.security.CheckoutAuthAndRateLimitInterceptor;
import com.salestorm.checkout.infrastructure.security.PerUserRateLimiter;
import com.salestorm.common.api.GlobalExceptionHandler;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CheckoutControllerTest {

    private MockMvc mockMvc;
    private CheckoutFacade checkoutFacade;
    private ObjectMapper objectMapper;
    private PerUserRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        checkoutFacade = Mockito.mock(CheckoutFacade.class);
        objectMapper = new ObjectMapper();
        rateLimiter = new PerUserRateLimiter(1000L);

        CheckoutController controller = new CheckoutController(checkoutFacade);
        CheckoutAuthAndRateLimitInterceptor interceptor = new CheckoutAuthAndRateLimitInterceptor(rateLimiter, objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(interceptor)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /api/v1/checkout/buy returns 201 CREATED when valid headers and payload are provided")
    void testBuySuccess() throws Exception {
        CheckoutBuyResult buyResult = new CheckoutBuyResult(
                "ORD-TEST-1",
                "RES-TEST-1",
                1001L,
                1L,
                1,
                new BigDecimal("99.99"),
                "USD",
                Instant.now().plusSeconds(300),
                "RESERVED"
        );

        Mockito.when(checkoutFacade.buy(eq(1001L), eq(1L), anyInt(), any(), anyString()))
                .thenReturn(buyResult);

        CheckoutBuyRequest request = new CheckoutBuyRequest(1L, 1, "SAVE10");

        mockMvc.perform(post("/api/v1/checkout/buy")
                        .header("X-Customer-Id", "1001")
                        .header("Idempotency-Key", "IDEMP-BUY-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value("ORD-TEST-1"))
                .andExpect(jsonPath("$.reservationId").value("RES-TEST-1"))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.totalAmount").value(99.99));
    }

    @Test
    @DisplayName("POST /api/v1/checkout/buy returns 401 UNAUTHORIZED when X-Customer-Id header is missing")
    void testBuyMissingAuthHeader() throws Exception {
        CheckoutBuyRequest request = new CheckoutBuyRequest(1L, 1, null);

        mockMvc.perform(post("/api/v1/checkout/buy")
                        .header("Idempotency-Key", "IDEMP-BUY-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Unauthorized: Missing X-Customer-Id header"));
    }

    @Test
    @DisplayName("POST /api/v1/checkout/pay returns 200 OK on successful payment")
    void testPaySuccess() throws Exception {
        CheckoutPayResult payResult = new CheckoutPayResult(
                "ORD-TEST-1",
                "PAY-TEST-1",
                "SUCCEEDED",
                new BigDecimal("99.99"),
                "USD",
                "gw_ref_123",
                null
        );

        Mockito.when(checkoutFacade.pay(eq(1001L), eq("ORD-TEST-1"), eq(new BigDecimal("99.99")), anyString()))
                .thenReturn(payResult);

        CheckoutPayRequest request = new CheckoutPayRequest("ORD-TEST-1", new BigDecimal("99.99"));

        mockMvc.perform(post("/api/v1/checkout/pay")
                        .header("X-Customer-Id", "1001")
                        .header("Idempotency-Key", "IDEMP-PAY-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("ORD-TEST-1"))
                .andExpect(jsonPath("$.paymentId").value("PAY-TEST-1"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    @DisplayName("GET /api/v1/checkout/{orderId}/status returns 200 OK with saga and order state")
    void testGetStatusSuccess() throws Exception {
        CheckoutStatusResult statusResult = new CheckoutStatusResult(
                "ORD-TEST-1",
                "DONE",
                "CONFIRMED",
                "RECORDED",
                "RES-TEST-1",
                new BigDecimal("99.99"),
                "USD",
                null,
                Instant.now()
        );

        Mockito.when(checkoutFacade.getStatus("ORD-TEST-1")).thenReturn(statusResult);

        mockMvc.perform(get("/api/v1/checkout/ORD-TEST-1/status")
                        .header("X-Customer-Id", "1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("ORD-TEST-1"))
                .andExpect(jsonPath("$.sagaStatus").value("DONE"))
                .andExpect(jsonPath("$.orderStatus").value("CONFIRMED"));
    }
}
