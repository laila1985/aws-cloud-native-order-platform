package com.example.orderplatform.messaging;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SetQueueAttributesRequest;

import java.util.Map;

/**
 * Provisions (get-or-create) the SNS topic, the SQS queues, and the
 * topic → queue subscriptions, then exposes their ARNs/URLs to the rest of the
 * application.
 * <p>
 * Provisioning is idempotent and best-effort: if the messaging backend is not
 * reachable at startup (e.g. LocalStack is down), the application still boots
 * and the consumers simply skip polling until resources become available.
 * <p>
 * Flow: {@code Order API → SNS topic → (SQS "order-processor-queue",
 * SQS "order-lambda-queue", SQS "order-email-queue", SQS "order-sms-queue")}.
 * The second queue stands in for a real AWS Lambda subscriber. The email and SMS
 * queues feed the {@code EmailNotificationHandler} and {@code SmsNotificationHandler}.
 */
@Component
public class MessagingResources {

    private static final Logger log = LoggerFactory.getLogger(MessagingResources.class);

    private final SnsClient snsClient;
    private final SqsClient sqsClient;
    private final String topicName;
    private final String processorQueueName;
    private final String lambdaQueueName;
    private final String emailQueueName;
    private final String smsQueueName;
    private final String region;

    private volatile String topicArn;
    private volatile String processorQueueUrl;
    private volatile String lambdaQueueUrl;
    private volatile String emailQueueUrl;
    private volatile String smsQueueUrl;

    public MessagingResources(SnsClient snsClient,
                              SqsClient sqsClient,
                              @Value("${aws.sns.topicName:order-events}") String topicName,
                              @Value("${aws.sqs.processorQueueName:order-processor-queue}") String processorQueueName,
                              @Value("${aws.sqs.lambdaQueueName:order-lambda-queue}") String lambdaQueueName,
                              @Value("${aws.sqs.emailQueueName:order-email-queue}") String emailQueueName,
                              @Value("${aws.sqs.smsQueueName:order-sms-queue}") String smsQueueName,
                              @Value("${aws.region:us-east-1}") String region) {
        this.snsClient = snsClient;
        this.sqsClient = sqsClient;
        this.topicName = topicName;
        this.processorQueueName = processorQueueName;
        this.lambdaQueueName = lambdaQueueName;
        this.emailQueueName = emailQueueName;
        this.smsQueueName = smsQueueName;
        this.region = region;
    }

    @PostConstruct
    void init() {
        try {
            topicArn = ensureTopic();
            processorQueueUrl = ensureQueue(processorQueueName);
            lambdaQueueUrl = ensureQueue(lambdaQueueName);
            emailQueueUrl = ensureQueue(emailQueueName);
            smsQueueUrl = ensureQueue(smsQueueName);
            subscribeQueue(topicArn, processorQueueUrl);
            subscribeQueue(topicArn, lambdaQueueUrl);
            subscribeQueue(topicArn, emailQueueUrl);
            subscribeQueue(topicArn, smsQueueUrl);
            log.info("Messaging resources ready. topic={}, processorQueue={}, lambdaQueue={}, emailQueue={}, smsQueue={}",
                    topicArn, processorQueueUrl, lambdaQueueUrl, emailQueueUrl, smsQueueUrl);
        } catch (Exception e) {
            log.warn("Could not provision messaging resources (is LocalStack running?); "
                    + "messaging will be inactive until restart.", e);
        }
    }

    private String ensureTopic() {
        return snsClient.createTopic(CreateTopicRequest.builder().name(topicName).build()).topicArn();
    }

    private String ensureQueue(String queueName) {
        String url = sqsClient.createQueue(CreateQueueRequest.builder().queueName(queueName).build()).queueUrl();
        // Ensure the queue is long-polling-friendly for the pollers.
        sqsClient.setQueueAttributes(SetQueueAttributesRequest.builder()
                .queueUrl(url)
                .attributes(Map.of(QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS, "5"))
                .build());
        return url;
    }

    private void subscribeQueue(String topicArn, String queueUrl) {
        String queueArn = sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)
                        .build())
                .attributes().get(QueueAttributeName.QUEUE_ARN);
        subscribe(topicArn, "sqs", queueArn);
    }

    private void subscribe(String topicArn, String protocol, String endpoint) {
        snsClient.subscribe(SubscribeRequest.builder()
                .topicArn(topicArn)
                .protocol(protocol)
                .endpoint(endpoint)
                .build());
    }

    public String topicArn() {
        return topicArn;
    }

    public String processorQueueUrl() {
        return processorQueueUrl;
    }

    public String lambdaQueueUrl() {
        return lambdaQueueUrl;
    }

    public String emailQueueUrl() {
        return emailQueueUrl;
    }

    public String smsQueueUrl() {
        return smsQueueUrl;
    }
}
