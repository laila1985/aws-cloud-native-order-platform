package com.example.orderplatform.messaging.notification;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

/**
 * The "SMS provider" stage — sends a text message through SNS (direct publish
 * to a phone number).
 */
@Component
public class SmsProvider {

    private final SnsClient snsClient;

    public SmsProvider(SnsClient snsClient) {
        this.snsClient = snsClient;
    }

    public void send(String phoneNumber, String messageText) {
        snsClient.publish(PublishRequest.builder()
                .phoneNumber(phoneNumber)
                .message(messageText)
                .build());
    }
}
