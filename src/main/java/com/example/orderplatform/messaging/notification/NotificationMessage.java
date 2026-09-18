package com.example.orderplatform.messaging.notification;

import java.time.Instant;
import java.util.Map;

/**
 * A notification message flowing through the email/SMS pipelines.
 * <p>
 * Both channels share {@code notificationId}, {@code type}, {@code channel},
 * {@code template}, {@code data}, and {@code createdAt}. Channel-specific fields
 * are nullable:
 * <ul>
 *   <li>EMAIL: {@code recipient} + {@code subject}</li>
 *   <li>SMS:   {@code phoneNumber} + {@code message}</li>
 * </ul>
 */
public record NotificationMessage(
        String notificationId,
        String type,
        String channel,
        String template,
        Map<String, Object> data,
        String recipient,
        String subject,
        String phoneNumber,
        String message,
        Instant createdAt) {
}
