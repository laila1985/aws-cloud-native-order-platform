package com.example.orderplatform.messaging.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.orderplatform.event.OrderCreatedEvent;
import com.example.orderplatform.model.Customer;
import com.example.orderplatform.messaging.MessagingResources;
import com.example.orderplatform.service.CustomerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * The "Email Lambda" — a pipeline that turns an order event into a sent email.
 *
 * <pre>
 *   SQS → Email Lambda → Validate → Load template → Build email → Email provider → Success
 * </pre>
 *
 * It listens on the "order-email-queue" (fed by SNS), builds a
 * {@link NotificationMessage} for the customer, and runs it through the stages.
 */
@Component
public class EmailNotificationHandler {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationHandler.class);

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final CustomerService customerService;
    private final NotificationValidator validator;
    private final TemplateService templateService;
    private final EmailProvider emailProvider;
    private final MessagingResources messagingResources;
    private final String currency;

    public EmailNotificationHandler(SqsClient sqsClient,
                                    ObjectMapper objectMapper,
                                    CustomerService customerService,
                                    NotificationValidator validator,
                                    TemplateService templateService,
                                    EmailProvider emailProvider,
                                    MessagingResources messagingResources,
                                    @Value("${aws.currency:AED}") String currency) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.customerService = customerService;
        this.validator = validator;
        this.templateService = templateService;
        this.emailProvider = emailProvider;
        this.messagingResources = messagingResources;
        this.currency = currency;
    }

    @Scheduled(fixedDelayString = "${aws.sqs.pollIntervalMs:5000}")
    public void poll() {
        String queueUrl = messagingResources.emailQueueUrl();
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
            log.error("[email-lambda] Failed to process message {}: {}", message.messageId(), e.getMessage(), e);
        } finally {
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build());
        }
    }

    /** Runs the explicit pipeline stages for an email notification. */
    void handle(OrderCreatedEvent event, Customer customer) {
        // Build the notification message (EMAIL channel).
        NotificationMessage notification = new NotificationMessage(
                "notif-" + UUID.randomUUID(),
                "ORDER_CREATED",
                "EMAIL",
                "ORDER_CREATED",
                dataFor(event, customer),
                customer.getEmail(),
                "Order " + event.orderId() + " confirmed",
                null,
                null,
                Instant.now());

        // Stage 1 — Validate message.
        validator.validate(notification);
        log.info("[email-lambda] Validated notification {}", notification.notificationId());

        // Stage 2 — Load template.
        String template = templateService.load(notification.template());
        log.info("[email-lambda] Loaded template {}", notification.template());

        // Stage 3 — Build email.
        String body = templateService.render(template, notification.data());

        // Stage 4 — Email provider.
        emailProvider.send(notification.recipient(), notification.subject(), body);

        // Stage 5 — Success.
        log.info("[email-lambda] Success: sent {} email to {} (notification {})",
                notification.type(), notification.recipient(), notification.notificationId());
    }

    private Map<String, Object> dataFor(OrderCreatedEvent event, Customer customer) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", event.orderId());
        data.put("customerName", customer.getName());
        data.put("totalAmount", event.totalAmount());
        data.put("currency", currency);
        return data;
    }

    private OrderCreatedEvent parseEvent(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        String payload = root.has("Message") ? root.get("Message").asText() : body;
        return objectMapper.readValue(payload, OrderCreatedEvent.class);
    }
}
