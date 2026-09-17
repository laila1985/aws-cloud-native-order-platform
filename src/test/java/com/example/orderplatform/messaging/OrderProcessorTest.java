package com.example.orderplatform.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.orderplatform.model.Order;
import com.example.orderplatform.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderProcessorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private SqsClient sqsClient;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private MessagingResources messagingResources;

    private OrderProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new OrderProcessor(sqsClient, OBJECT_MAPPER, orderRepository, messagingResources);
    }

    @Test
    @DisplayName("poll() marks an order PROCESSED and deletes the message")
    void poll_marksOrderProcessedAndDeletesMessage() {
        when(messagingResources.processorQueueUrl()).thenReturn("http://queue");
        // SNS wraps the event in a notification envelope.
        String envelope = "{\"Type\":\"Notification\",\"Message\":"
                + "\"{\\\"orderId\\\":\\\"id-1\\\",\\\"customerId\\\":\\\"cust-1\\\",\\\"status\\\":\\\"CREATED\\\"}\"}";
        Message message = Message.builder().messageId("msg-1").body(envelope).receiptHandle("rh-1").build();
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(message).build());

        Order order = new Order();
        order.setOrderId("id-1");
        when(orderRepository.findById("id-1")).thenReturn(Optional.of(order));

        processor.poll();

        assertThat(order.getStatus()).isEqualTo("PROCESSED");
        verify(orderRepository).save(order);
        ArgumentCaptor<DeleteMessageRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(sqsClient).deleteMessage(deleteCaptor.capture());
        assertThat(deleteCaptor.getValue().receiptHandle()).isEqualTo("rh-1");
    }

    @Test
    @DisplayName("poll() does nothing when the queue is unavailable")
    void poll_noopWhenQueueUnavailable() {
        when(messagingResources.processorQueueUrl()).thenReturn(null);

        processor.poll();

        verify(sqsClient, never()).receiveMessage(any(ReceiveMessageRequest.class));
    }
}
