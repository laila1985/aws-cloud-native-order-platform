package com.example.orderplatform.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable event published to SNS when an order is created.
 * Jackson (via Spring Boot) serializes this to JSON for the SNS message body.
 */
public record OrderCreatedEvent(
        String orderId,
        String customerId,
        String status,
        BigDecimal totalAmount,
        Instant createdAt) {
}
