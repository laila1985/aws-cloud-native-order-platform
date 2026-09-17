package com.example.orderplatform.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.orderplatform.event.OrderCreatedEvent;
import com.example.orderplatform.model.Order;
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
 * SQS consumer that processes {@link OrderCreatedEvent}s from the
 * "order-processor-queue". On receipt it marks the corresponding order as
 * {@code PROCESSED} in DynamoDB.
 * <p>
 * This is the {@code SQS → Order Processor} branch of the messaging flow.
 */
@Component
public class OrderProcessor {

    private static final Logger log = LoggerFactory.getLogger(OrderProcessor.class);

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final OrderRepository orderRepository;
    private final MessagingResources messagingResources;

    public OrderProcessor(SqsClient sqsClient,
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
        String queueUrl = messagingResources.processorQueueUrl();
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
                order.setStatus("PROCESSED");
                orderRepository.save(order);
                log.info("Order {} marked PROCESSED", event.orderId());
            });
        } catch (Exception e) {
            log.error("Failed to process message {}", message.messageId(), e);
        } finally {
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle())
                    .build());
        }
    }

    /**
     * SNS delivers to SQS by wrapping the payload in a notification envelope:
     * the message body is JSON whose {@code "Message"} field holds our event.
     */
    private OrderCreatedEvent parseEvent(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        String payload = root.has("Message") ? root.get("Message").asText() : body;
        return objectMapper.readValue(payload, OrderCreatedEvent.class);
    }
}
