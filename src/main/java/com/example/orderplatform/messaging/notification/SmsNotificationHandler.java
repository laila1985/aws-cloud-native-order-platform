package com.example.orderplatform.messaging.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.orderplatform.event.OrderCreatedEvent;
import com.example.orderplatform.model.Customer;
import com.example.orderplatform.messaging.MessagingResources;
import com.example.orderplatform.service.CustomerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The "SMS Lambda" — a pipeline that turns an order event into a sent SMS.
 *
 * <pre>
 *   SQS → SMS Lambda → Validate → Build SMS → SMS provider → Success
 * </pre>
 *
 * It listens on the "order-sms-queue" (fed by SNS), builds a
 * {@link NotificationMessage} for the customer, and runs it through the stages.
 */
@Component
public class SmsNotificationHandler {

    private static final Logger log = LoggerFactory.getLogger(SmsNotificationHandler.class);

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final CustomerService customerService;
    private final NotificationValidator validator;
    private final SmsProvider smsProvider;
    private final MessagingResources messagingResources;

    public SmsNotificationHandler(SqsClient sqsClient,
                                  ObjectMapper objectMapper,
                                  CustomerService customerService,
                                  NotificationValidator validator,
                                  SmsProvider smsProvider,
                                  MessagingResources messagingResources) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.customerService = customerService;
        this.validator = validator;
        this.smsProvider = smsProvider;
        this.messagingResources = messagingResources;
    }

    @Scheduled(fixedDelayString = "${aws.sqs.pollIntervalMs:5000}")
    public void poll() {
        String queueUrl = messagingResources.smsQueueUrl();
        if (queueUrl == null) {
            return;
        }
        List<software.amazon.awssdk.services.sqs.model.Message> messages =
                sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(5)
                        .build()).messages();
        for (software.amazon.awssdk.services.sqs.model.Message message : messages) {
            process(message, queueUrl);
        }
    }

    private void process(software.amazon.awssdk.services.sqs.model.Message message, String queueUrl) {
        try {
            OrderCreatedEvent event = parseEvent(message.body());
            Customer customer = customerService.findById(event.customerId());
            handle(event, customer);
        } catch (Exception e) {
            log.error("[sms-lambda] Failed to process message {}: {}", message.messageId(), e.getMessage(), e);
        } finally {
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build());
        }
    }

    /** Runs the explicit pipeline stages for an SMS notification. */
    void handle(OrderCreatedEvent event, Customer customer) {
        // Build the notification message (SMS channel).
        String text = "Your order " + event.orderId() + " has been confirmed.";
        NotificationMessage notification = new NotificationMessage(
                "notif-" + UUID.randomUUID(),
                "ORDER_CREATED",
                "SMS",
                "ORDER_CREATED",
                dataFor(event, customer),
                null,
                null,
                customer.getPhoneNumber(),
                text,
                Instant.now());

        // Stage 1 — Validate message.
        validator.validate(notification);
        log.info("[sms-lambda] Validated notification {}", notification.notificationId());

        // Stage 2 — Build SMS (message is pre-built above).
        log.info("[sms-lambda] Built SMS for {}", notification.phoneNumber());

        // Stage 3 — SMS provider.
        smsProvider.send(notification.phoneNumber(), notification.message());

        // Stage 4 — Success.
        log.info("[sms-lambda] Success: sent {} SMS to {} (notification {})",
                notification.type(), notification.phoneNumber(), notification.notificationId());
    }

    private Map<String, Object> dataFor(OrderCreatedEvent event, Customer customer) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", event.orderId());
        data.put("customerName", customer.getName());
        return data;
    }

    private OrderCreatedEvent parseEvent(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        String payload = root.has("Message") ? root.get("Message").asText() : body;
        return objectMapper.readValue(payload, OrderCreatedEvent.class);
    }
}
