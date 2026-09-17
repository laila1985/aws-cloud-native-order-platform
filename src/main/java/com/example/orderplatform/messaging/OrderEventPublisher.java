package com.example.orderplatform.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.orderplatform.event.OrderCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

/**
 * Publishes order events to SNS (fire-and-forget). SNS fans the message out to
 * the SQS queue (Order Processor) and the Lambda-like queue.
 */
@Component
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final MessagingResources messagingResources;

    public OrderEventPublisher(SnsClient snsClient,
                               ObjectMapper objectMapper,
                               MessagingResources messagingResources) {
        this.snsClient = snsClient;
        this.objectMapper = objectMapper;
        this.messagingResources = messagingResources;
    }

    public void publish(OrderCreatedEvent event) {
        String topicArn = messagingResources.topicArn();
        if (topicArn == null || topicArn.isBlank()) {
            log.warn("SNS topic is not available; skipping publish for order {}", event.orderId());
            return;
        }
        try {
            String body = objectMapper.writeValueAsString(event);
            snsClient.publish(PublishRequest.builder()
                    .topicArn(topicArn)
                    .message(body)
                    .build());
            log.info("Published OrderCreatedEvent for order {}", event.orderId());
        } catch (Exception e) {
            // Publishing is best-effort; it must not fail the order creation flow.
            log.error("Failed to publish OrderCreatedEvent for order {}", event.orderId(), e);
        }
    }
}
