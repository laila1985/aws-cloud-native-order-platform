package com.example.orderplatform.service;

import com.example.orderplatform.exception.CustomerNotFoundException;
import com.example.orderplatform.exception.OrderNotFoundException;
import com.example.orderplatform.messaging.publisher.OrderEventPublisher;
import com.example.orderplatform.model.Customer;
import com.example.orderplatform.model.Order;
import com.example.orderplatform.model.OrderItem;
import com.example.orderplatform.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderEventPublisher orderEventPublisher;

    @Mock
    private CustomerService customerService;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, orderEventPublisher, customerService);
    }

    private OrderItem item(String productId, int quantity, String unitPrice) {
        OrderItem item = new OrderItem();
        item.setProductId(productId);
        item.setProductName("Product " + productId);
        item.setQuantity(quantity);
        item.setUnitPrice(new BigDecimal(unitPrice));
        return item;
    }

    @Test
    @DisplayName("create() auto-generates id, status and createdAt, and computes total")
    void create_generatesDefaultsAndComputesTotal() {
        when(customerService.findById("cust-1")).thenReturn(new Customer());

        Order order = new Order();
        order.setCustomerId("cust-1");
        order.setItems(List.of(item("p-1", 2, "10.00"), item("p-2", 1, "5.50")));

        Order result = orderService.create(order);

        assertThat(result.getOrderId()).isNotBlank();
        assertThat(result.getStatus()).isEqualTo("CREATED");
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getTotalAmount()).isEqualByComparingTo("25.50");
        verify(orderRepository).save(result);
        verify(orderEventPublisher).publish(any());
    }

    @Test
    @DisplayName("create() preserves a provided orderId and status")
    void create_preservesProvidedIdAndStatus() {
        when(customerService.findById("cust-1")).thenReturn(new Customer());

        Order order = new Order();
        order.setOrderId("fixed-id");
        order.setCustomerId("cust-1");
        order.setStatus("PAID");
        order.setItems(List.of(item("p-1", 1, "9.99")));

        Order result = orderService.create(order);

        assertThat(result.getOrderId()).isEqualTo("fixed-id");
        assertThat(result.getStatus()).isEqualTo("PAID");
        assertThat(result.getTotalAmount()).isEqualByComparingTo("9.99");
    }

    @Test
    @DisplayName("create() with no items yields a zero total")
    void create_noItemsYieldsZeroTotal() {
        when(customerService.findById("cust-1")).thenReturn(new Customer());

        Order order = new Order();
        order.setCustomerId("cust-1");

        Order result = orderService.create(order);

        assertThat(result.getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("create() throws CustomerNotFoundException when customerId is blank")
    void create_throwsWhenCustomerIdBlank() {
        Order order = new Order();
        order.setCustomerId("  ");

        assertThatThrownBy(() -> orderService.create(order))
                .isInstanceOf(CustomerNotFoundException.class);

        verify(orderRepository, never()).save(any());
        verify(orderEventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("create() throws CustomerNotFoundException when the customer does not exist")
    void create_throwsWhenCustomerMissing() {
        when(customerService.findById("ghost")).thenThrow(new CustomerNotFoundException("ghost"));

        Order order = new Order();
        order.setCustomerId("ghost");

        assertThatThrownBy(() -> orderService.create(order))
                .isInstanceOf(CustomerNotFoundException.class)
                .hasMessageContaining("ghost");

        verify(orderRepository, never()).save(any());
        verify(orderEventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("findById() returns the order when present")
    void findById_returnsOrder() {
        Order order = new Order();
        order.setOrderId("id-1");
        when(orderRepository.findById("id-1")).thenReturn(Optional.of(order));

        Order result = orderService.findById("id-1");

        assertThat(result).isSameAs(order);
    }

    @Test
    @DisplayName("findById() throws OrderNotFoundException when missing")
    void findById_throwsWhenMissing() {
        when(orderRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.findById("missing"))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("findAll() delegates to the repository")
    void findAll_delegates() {
        Order order = new Order();
        order.setOrderId("id-1");
        when(orderRepository.findAll()).thenReturn(List.of(order));

        assertThat(orderService.findAll()).containsExactly(order);
    }

    @Test
    @DisplayName("update() changes fields and recomputes total when items provided")
    void update_recomputesTotalWhenItemsProvided() {
        Order existing = new Order();
        existing.setOrderId("id-1");
        existing.setStatus("CREATED");
        existing.setCustomerId("cust-old");
        existing.setTotalAmount(new BigDecimal("10.00"));
        when(orderRepository.findById("id-1")).thenReturn(Optional.of(existing));

        Order updated = new Order();
        updated.setStatus("SHIPPED");
        updated.setItems(List.of(item("p-1", 3, "4.00")));

        Order result = orderService.update("id-1", updated);

        assertThat(result.getStatus()).isEqualTo("SHIPPED");
        assertThat(result.getCustomerId()).isEqualTo("cust-old"); // unchanged
        assertThat(result.getTotalAmount()).isEqualByComparingTo("12.00");
        verify(orderRepository).save(existing);
    }

    @Test
    @DisplayName("update() leaves total untouched when no items provided")
    void update_keepsTotalWhenNoItemsProvided() {
        Order existing = new Order();
        existing.setOrderId("id-1");
        existing.setStatus("CREATED");
        existing.setTotalAmount(new BigDecimal("10.00"));
        when(orderRepository.findById("id-1")).thenReturn(Optional.of(existing));

        Order updated = new Order();
        updated.setStatus("PAID");

        Order result = orderService.update("id-1", updated);

        assertThat(result.getTotalAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("update() throws when order is missing")
    void update_throwsWhenMissing() {
        when(orderRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.update("missing", new Order()))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    @DisplayName("updateStatus() changes the status and persists the order")
    void updateStatus_changesStatusAndPersists() {
        Order existing = new Order();
        existing.setOrderId("id-1");
        existing.setStatus("CREATED");
        when(orderRepository.findById("id-1")).thenReturn(Optional.of(existing));

        Order result = orderService.updateStatus("id-1", "PROCESSED");

        assertThat(result.getStatus()).isEqualTo("PROCESSED");
        verify(orderRepository).save(existing);
    }

    @Test
    @DisplayName("updateStatus() throws when order is missing")
    void updateStatus_throwsWhenMissing() {
        when(orderRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.updateStatus("missing", "PROCESSED"))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    @DisplayName("delete() removes an existing order")
    void delete_removesExistingOrder() {
        Order existing = new Order();
        existing.setOrderId("id-1");
        when(orderRepository.findById("id-1")).thenReturn(Optional.of(existing));

        orderService.delete("id-1");

        verify(orderRepository).delete("id-1");
    }

    @Test
    @DisplayName("delete() throws when order is missing and never deletes")
    void delete_throwsWhenMissing() {
        when(orderRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.delete("missing"))
                .isInstanceOf(OrderNotFoundException.class);

        verify(orderRepository, never()).delete(any());
    }
}
