package com.salestorm.inventory.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salestorm.common.api.GlobalExceptionHandler;
import com.salestorm.inventory.api.dto.CreateReservationRequest;
import com.salestorm.inventory.application.ReservationService;
import com.salestorm.inventory.application.dto.ReservationResult;
import com.salestorm.inventory.domain.DuplicateRequestConflictException;
import com.salestorm.inventory.domain.ReservationStatus;
import com.salestorm.inventory.domain.SoldOutException;
import com.salestorm.inventory.domain.TemporarilyUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReservationControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private final AtomicReference<ReservationResult> mockResult = new AtomicReference<>();
    private final AtomicReference<RuntimeException> mockException = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockResult.set(null);
        mockException.set(null);

        // Standalone fake service avoiding reflection/bytecode instrumentation issues
        ReservationService fakeService = new ReservationService(null, null, null, null, null) {
            @Override
            public ReservationResult reserve(Long customerId, Long productId, String idempotencyKey) {
                if (mockException.get() != null) {
                    throw mockException.get();
                }
                return mockResult.get();
            }
        };

        mockMvc = MockMvcBuilders.standaloneSetup(new ReservationController(fakeService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /api/v1/reservations returns 201 Created on fresh reservation")
    void testCreateReservationSuccess() throws Exception {
        String resId = UUID.randomUUID().toString();
        ReservationResult result = new ReservationResult(
                resId, 1L, 101L, 1, ReservationStatus.RESERVED, Instant.now().plusSeconds(300), "KEY-1", false
        );
        mockResult.set(result);

        CreateReservationRequest request = new CreateReservationRequest(101L, 1L);

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", "KEY-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/reservations/" + resId))
                .andExpect(jsonPath("$.reservationId").value(resId))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.quantity").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/reservations returns 200 OK on duplicate key replay")
    void testCreateReservationDuplicateKeyReturns200() throws Exception {
        String resId = UUID.randomUUID().toString();
        ReservationResult result = new ReservationResult(
                resId, 1L, 101L, 1, ReservationStatus.RESERVED, Instant.now().plusSeconds(300), "KEY-1", true
        );
        mockResult.set(result);

        CreateReservationRequest request = new CreateReservationRequest(101L, 1L);

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", "KEY-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(resId));
    }

    @Test
    @DisplayName("POST /api/v1/reservations returns 400 Bad Request when Idempotency-Key header is missing")
    void testMissingIdempotencyKeyHeaderReturns400() throws Exception {
        CreateReservationRequest request = new CreateReservationRequest(101L, 1L);

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /api/v1/reservations returns 409 Conflict on payload mismatch for same key")
    void testDuplicateKeyDifferentPayloadReturns409() throws Exception {
        mockException.set(new DuplicateRequestConflictException("Idempotency key was used with different payload"));

        CreateReservationRequest request = new CreateReservationRequest(101L, 1L);

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", "KEY-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("POST /api/v1/reservations returns 429 Too Many Requests when stock is temporarily held")
    void testTemporarilyUnavailableReturns429() throws Exception {
        mockException.set(new TemporarilyUnavailableException("Stock is temporarily reserved by other customers"));

        CreateReservationRequest request = new CreateReservationRequest(101L, 1L);

        mockMvc.perform(post("/api/v1/reservations")
                        .header("Idempotency-Key", "KEY-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));
    }
}
