package com.salestorm.order.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salestorm.common.api.GlobalExceptionHandler;
import com.salestorm.order.api.dto.CancelOrderRequest;
import com.salestorm.order.application.OrderService;
import com.salestorm.order.application.dto.OrderItemDto;
import com.salestorm.order.application.dto.OrderResult;
import com.salestorm.order.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerTest {

    private MockMvc mockMvc;
    private OrderService orderService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        orderService = Mockito.mock(OrderService.class);
        objectMapper = new ObjectMapper();

        OrderController controller = new OrderController(orderService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/orders/{id} returns 200 OK with order details")
    void testGetOrderSuccess() throws Exception {
        OrderResult mockResult = new OrderResult(
                "ord_12345",
                1001L,
                "res_999",
                OrderStatus.CONFIRMED,
                new BigDecimal("150.00"),
                "USD",
                null,
                Instant.now(),
                List.of(new OrderItemDto(1L, 1, new BigDecimal("150.00"), new BigDecimal("150.00"))),
                List.of(new OrderResult.OrderStatusHistoryDto("CREATED", "CONFIRMED", "Payment received", "e1", Instant.now()))
        );

        Mockito.when(orderService.getOrderById("ord_12345")).thenReturn(Optional.of(mockResult));

        mockMvc.perform(get("/api/v1/orders/ord_12345"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("ord_12345"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.totalAmount").value(150.00));
    }

    @Test
    @DisplayName("GET /api/v1/orders/{id} returns 404 NOT FOUND for unknown order")
    void testGetOrderNotFound() throws Exception {
        Mockito.when(orderService.getOrderById("unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/orders/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/orders/{id}/cancel returns 200 OK with cancelled order status")
    void testCancelOrderSuccess() throws Exception {
        OrderResult cancelledResult = new OrderResult(
                "ord_12345",
                1001L,
                "res_999",
                OrderStatus.CANCELLED,
                new BigDecimal("150.00"),
                "USD",
                "Customer requested",
                Instant.now(),
                List.of(),
                List.of()
        );

        Mockito.when(orderService.onCancelOrder(eq("ord_12345"), eq("Customer requested"), any()))
                .thenReturn(cancelledResult);

        CancelOrderRequest request = new CancelOrderRequest("Customer requested");

        mockMvc.perform(post("/api/v1/orders/ord_12345/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("ord_12345"))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("Customer requested"));
    }
}
