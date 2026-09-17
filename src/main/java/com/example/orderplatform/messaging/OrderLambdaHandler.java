package com.example.orderplatform.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.orderplatform.event.OrderCreatedEvent;
import com.example.orderplatform.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.List;

/**
 * Stands in for a real AWS Lambda function that is subscribed to the SNS topic.
 * <p>
 * In AWS this branch is implemented as a Lambda (e.g. {@code order-notifier})
 * triggered by SNS; locally we emulate it as a second SQS queue
 * ({@code order-lambda-queue}) consumed by this handler. On receipt it marks the
 * order as {@code NOTIFIED}, simulating the Lambda's side effect.
 * <p>
 * To replace this with a real Lambda, deploy the handler as a Lambda function
 * and add an SNS subscription with {@code protocol = "lambda"} — the SQS queue
 * and this poller then become unnecessary.
 */
@Component
public class OrderLambdaHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderLambdaHandler.class);

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final OrderRepository orderRepository;
    private final MessagingResources messagingResources;

    public OrderLambdaHandler(SqsClient sqsClient,
                              ObjectMapper objectMapper,
                              OrderRepository orderRepository,
                              MessagingResources messagingResources) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.orderRepository = orderRepository;
        this.messagingResources = messagingResources;
    }

    @Scheduled(fixedDelayString = "${aws.sqs.pollIntervalMs:5000}")
    public void poll() {
        String queueUrl = messagingResources.lambdaQueueUrl();
        if (queueUrl == null) {
            return;
        }
        List<Message> messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(5)
                        .build())
                .messages();
        for (Message message : messages) {
            process(message, queueUrl);
        }
    }

    private void process(Message message, String queueUrl) {
        try {
            OrderCreatedEvent event = parseEvent(message.body());
            orderRepository.findById(event.orderId()).ifPresent(order -> {
                order.setStatus("NOTIFIED");
                orderRepository.save(order);
                log.info("[lambda-sim] Order {} marked NOTIFIED", event.orderId());
            });
        } catch (Exception e) {
            log.error("[lambda-sim] Failed to process message {}", message.messageId(), e);
        } finally {
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build());
        }
    }

    private OrderCreatedEvent parseEvent(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        String payload = root.has("Message") ? root.get("Message").asText() : body;
        return objectMapper.readValue(payload, OrderCreatedEvent.class);
    }
}
