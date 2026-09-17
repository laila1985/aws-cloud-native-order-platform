package com.example.orderplatform.service;

import com.example.orderplatform.event.OrderCreatedEvent;
import com.example.orderplatform.messaging.OrderEventPublisher;
import com.example.orderplatform.model.Order;
import com.example.orderplatform.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Business logic for orders.
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher orderEventPublisher;

    public OrderService(OrderRepository orderRepository, OrderEventPublisher orderEventPublisher) {
        this.orderRepository = orderRepository;
        this.orderEventPublisher = orderEventPublisher;
    }

    public Order create(Order order) {
        if (order.getOrderId() == null || order.getOrderId().isBlank()) {
            order.setOrderId(UUID.randomUUID().toString());
        }
        if (order.getCreatedAt() == null) {
            order.setCreatedAt(Instant.now());
        }
        if (order.getStatus() == null || order.getStatus().isBlank()) {
            order.setStatus("CREATED");
        }
        order.setTotalAmount(computeTotal(order));
        orderRepository.save(order);

        // Fan-out: publish the creation event to SNS (best-effort, async consumers).
        orderEventPublisher.publish(new OrderCreatedEvent(
                order.getOrderId(),
                order.getCustomerId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCreatedAt()));

        return order;
    }

    public Order findById(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    public List<Order> findAll() {
        return orderRepository.findAll();
    }

    public Order update(String orderId, Order updated) {
        Order existing = findById(orderId);
        existing.setStatus(updated.getStatus() != null ? updated.getStatus() : existing.getStatus());
        existing.setCustomerId(updated.getCustomerId() != null ? updated.getCustomerId() : existing.getCustomerId());
        if (updated.getItems() != null) {
            existing.setItems(updated.getItems());
            existing.setTotalAmount(computeTotal(existing));
        }
        orderRepository.save(existing);
        return existing;
    }

    public void delete(String orderId) {
        findById(orderId);
        orderRepository.delete(orderId);
    }

    private BigDecimal computeTotal(Order order) {
        if (order.getItems() == null) {
            return BigDecimal.ZERO;
        }
        return order.getItems().stream()
                .map(item -> {
                    BigDecimal price = item.getUnitPrice() == null ? BigDecimal.ZERO : item.getUnitPrice();
                    BigDecimal qty = item.getQuantity() == null ? BigDecimal.ZERO : BigDecimal.valueOf(item.getQuantity());
                    return price.multiply(qty);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
