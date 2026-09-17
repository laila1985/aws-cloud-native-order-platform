package com.example.orderplatform.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.example.orderplatform.event.OrderCreatedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderEventPublisherTest {

    // Matches Spring Boot's auto-configured mapper: supports java.time types.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Mock
    private SnsClient snsClient;

    @Mock
    private MessagingResources messagingResources;

    @Test
    @DisplayName("publish() sends the event JSON to the SNS topic")
    void publish_sendsJsonToTopic() throws Exception {
        when(messagingResources.topicArn()).thenReturn("arn:aws:sns:us-east-1:000000000000:order-events");
        doReturn(PublishResponse.builder().build()).when(snsClient).publish(any(PublishRequest.class));

        OrderEventPublisher publisher = new OrderEventPublisher(snsClient, OBJECT_MAPPER, messagingResources);
        OrderCreatedEvent event = new OrderCreatedEvent(
                "id-1", "cust-1", "CREATED", new BigDecimal("25.50"), Instant.parse("2026-01-01T00:00:00Z"));

        publisher.publish(event);

        ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
        verify(snsClient).publish(captor.capture());
        assertThat(captor.getValue().topicArn()).isEqualTo("arn:aws:sns:us-east-1:000000000000:order-events");

        OrderCreatedEvent parsed = OBJECT_MAPPER.readValue(captor.getValue().message(), OrderCreatedEvent.class);
        assertThat(parsed.orderId()).isEqualTo("id-1");
        assertThat(parsed.totalAmount()).isEqualByComparingTo("25.50");
    }

    @Test
    @DisplayName("publish() skips (no call to SNS) when the topic is unavailable")
    void publish_skipsWhenTopicUnavailable() {
        when(messagingResources.topicArn()).thenReturn(null);

        OrderEventPublisher publisher = new OrderEventPublisher(snsClient, OBJECT_MAPPER, messagingResources);
        publisher.publish(new OrderCreatedEvent("id-1", "cust-1", "CREATED", null, null));

        verify(snsClient, never()).publish(any(PublishRequest.class));
    }

    @Test
    @DisplayName("publish() swallows SNS errors and does not propagate")
    void publish_swallowsSnsErrors() {
        when(messagingResources.topicArn()).thenReturn("arn:aws:sns:us-east-1:000000000000:order-events");
        doThrow(new RuntimeException("SNS down")).when(snsClient).publish(any(PublishRequest.class));

        OrderEventPublisher publisher = new OrderEventPublisher(snsClient, OBJECT_MAPPER, messagingResources);
        // must not throw
        publisher.publish(new OrderCreatedEvent("id-1", "cust-1", "CREATED", null, null));
    }
}
