package com.salestorm.common.api;

import com.salestorm.common.infrastructure.filter.TraceIdFilter;
import com.salestorm.inventory.domain.DuplicateRequestConflictException;
import com.salestorm.inventory.domain.InvalidStateTransitionException;
import com.salestorm.inventory.domain.SoldOutException;
import com.salestorm.inventory.domain.StockDepletedException;
import com.salestorm.inventory.domain.TemporarilyUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Global exception handler providing uniform ApiError responses across all controllers.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationExceptions(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        List<ApiError.FieldErrorDetail> fieldErrors = ex.getBindingResult()
                .getAllErrors()
                .stream()
                .map(error -> {
                    String fieldName = (error instanceof FieldError fieldError)
                            ? fieldError.getField()
                            : error.getObjectName();
                    Object rejectedValue = (error instanceof FieldError fieldError)
                            ? fieldError.getRejectedValue()
                            : null;
                    return ApiError.FieldErrorDetail.builder()
                            .field(fieldName)
                            .rejectedValue(rejectedValue)
                            .message(error.getDefaultMessage())
                            .build();
                })
                .collect(Collectors.toList());

        ApiError error = buildError(
                HttpStatus.BAD_REQUEST,
                "Validation Failed",
                request.getRequestURI(),
                fieldErrors
        );

        log.warn("Validation error on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException ex,
            HttpServletRequest request
    ) {
        List<ApiError.FieldErrorDetail> fieldErrors = ex.getConstraintViolations()
                .stream()
                .map(violation -> ApiError.FieldErrorDetail.builder()
                        .field(violation.getPropertyPath().toString())
                        .rejectedValue(violation.getInvalidValue())
                        .message(violation.getMessage())
                        .build())
                .collect(Collectors.toList());

        ApiError error = buildError(
                HttpStatus.BAD_REQUEST,
                "Constraint Violation",
                request.getRequestURI(),
                fieldErrors
        );

        log.warn("Constraint violation on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(SoldOutException.class)
    public ResponseEntity<ApiError> handleSoldOutException(
            SoldOutException ex,
            HttpServletRequest request
    ) {
        ApiError error = buildError(
                HttpStatus.CONFLICT,
                ex.getMessage(),
                request.getRequestURI(),
                null
        );
        log.info("Sold out request on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(TemporarilyUnavailableException.class)
    public ResponseEntity<ApiError> handleTemporarilyUnavailableException(
            TemporarilyUnavailableException ex,
            HttpServletRequest request
    ) {
        ApiError error = buildError(
                HttpStatus.TOO_MANY_REQUESTS,
                ex.getMessage(),
                request.getRequestURI(),
                null
        );
        log.info("Temporarily unavailable on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(error);
    }

    @ExceptionHandler({DuplicateRequestConflictException.class, InvalidStateTransitionException.class, StockDepletedException.class})
    public ResponseEntity<ApiError> handleConflictExceptions(
            RuntimeException ex,
            HttpServletRequest request
    ) {
        ApiError error = buildError(
                HttpStatus.CONFLICT,
                ex.getMessage(),
                request.getRequestURI(),
                null
        );
        log.warn("Conflict on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatusException(
            ResponseStatusException ex,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        ApiError error = buildError(
                status,
                ex.getReason() != null ? ex.getReason() : ex.getMessage(),
                request.getRequestURI(),
                null
        );

        log.warn("ResponseStatusException on [{} {}]: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(status).body(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgumentException(
            IllegalArgumentException ex,
            HttpServletRequest request
    ) {
        ApiError error = buildError(
                HttpStatus.BAD_REQUEST,
                ex.getMessage(),
                request.getRequestURI(),
                null
        );

        log.warn("Illegal argument on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalStateException(
            IllegalStateException ex,
            HttpServletRequest request
    ) {
        ApiError error = buildError(
                HttpStatus.CONFLICT,
                ex.getMessage(),
                request.getRequestURI(),
                null
        );

        log.warn("Illegal state / conflict on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class)
    public ResponseEntity<ApiError> handleCircuitBreakerOpen(
            io.github.resilience4j.circuitbreaker.CallNotPermittedException ex,
            HttpServletRequest request
    ) {
        ApiError error = buildError(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Payment gateway is temporarily unavailable due to high error rates. Please retry shortly.",
                request.getRequestURI(),
                null
        );

        log.warn("Circuit breaker open on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "30")
                .body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGenericException(
            Exception ex,
            HttpServletRequest request
    ) {
        log.error("Unhandled exception processing request [{} {}]", request.getMethod(), request.getRequestURI(), ex);

        ApiError error = buildError(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected internal error occurred. Please contact support with the trace ID.",
                request.getRequestURI(),
                null
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    private ApiError buildError(
            HttpStatus status,
            String message,
            String path,
            List<ApiError.FieldErrorDetail> fieldErrors
    ) {
        String traceId = MDC.get(TraceIdFilter.MDC_TRACE_ID_KEY);

        return ApiError.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(path)
                .traceId(traceId)
                .fieldErrors(fieldErrors)
                .build();
    }
}
