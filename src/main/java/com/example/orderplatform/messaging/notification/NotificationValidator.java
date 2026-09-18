package com.example.orderplatform.messaging.notification;

import org.springframework.stereotype.Component;

/**
 * The "validate message" stage — checks a notification message has the required
 * fields for its channel before it is sent.
 */
@Component
public class NotificationValidator {

    public void validate(NotificationMessage message) {
        if (message.notificationId() == null || message.notificationId().isBlank()) {
            throw new IllegalArgumentException("notificationId is required");
        }
        if (message.channel() == null || message.channel().isBlank()) {
            throw new IllegalArgumentException("channel is required");
        }
        if ("EMAIL".equals(message.channel())) {
            if (message.recipient() == null || message.recipient().isBlank()) {
                throw new IllegalArgumentException("EMAIL channel requires recipient");
            }
        } else if ("SMS".equals(message.channel())) {
            if (message.phoneNumber() == null || message.phoneNumber().isBlank()) {
                throw new IllegalArgumentException("SMS channel requires phoneNumber");
            }
        } else {
            throw new IllegalArgumentException("Unsupported channel: " + message.channel());
        }
    }
}
