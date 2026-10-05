package com.salestorm.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Standardized API error response payload.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiError {

    private final Instant timestamp;
    private final int status;
    private final String error;
    private final String message;
    private final String path;
    private final String traceId;
    private final List<FieldErrorDetail> fieldErrors;

    private ApiError(Builder builder) {
        this.timestamp = builder.timestamp;
        this.status = builder.status;
        this.error = builder.error;
        this.message = builder.message;
        this.path = builder.path;
        this.traceId = builder.traceId;
        this.fieldErrors = builder.fieldErrors;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public int getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    public String getPath() {
        return path;
    }

    public String getTraceId() {
        return traceId;
    }

    public List<FieldErrorDetail> getFieldErrors() {
        return fieldErrors;
    }

    public static class FieldErrorDetail {
        private final String field;
        private final Object rejectedValue;
        private final String message;

        public FieldErrorDetail(String field, Object rejectedValue, String message) {
            this.field = field;
            this.rejectedValue = rejectedValue;
            this.message = message;
        }

        public static Builder builder() {
            return new Builder();
        }

        public String getField() {
            return field;
        }

        public Object getRejectedValue() {
            return rejectedValue;
        }

        public String getMessage() {
            return message;
        }

        public static class Builder {
            private String field;
            private Object rejectedValue;
            private String message;

            public Builder field(String field) {
                this.field = field;
                return this;
            }

            public Builder rejectedValue(Object rejectedValue) {
                this.rejectedValue = rejectedValue;
                return this;
            }

            public Builder message(String message) {
                this.message = message;
                return this;
            }

            public FieldErrorDetail build() {
                return new FieldErrorDetail(field, rejectedValue, message);
            }
        }
    }

    public static class Builder {
        private Instant timestamp;
        private int status;
        private String error;
        private String message;
        private String path;
        private String traceId;
        private List<FieldErrorDetail> fieldErrors;

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder status(int status) {
            this.status = status;
            return this;
        }

        public Builder error(String error) {
            this.error = error;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder fieldErrors(List<FieldErrorDetail> fieldErrors) {
            this.fieldErrors = fieldErrors;
            return this;
        }

        public ApiError build() {
            return new ApiError(this);
        }
    }
}
