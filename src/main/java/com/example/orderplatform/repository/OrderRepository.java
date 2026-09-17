package com.example.orderplatform.repository;

import com.example.orderplatform.model.Order;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Data access layer for {@link Order} backed by the DynamoDB Enhanced Client.
 */
@Repository
public class OrderRepository {

    private final DynamoDbTable<Order> orderTable;

    public OrderRepository(DynamoDbTable<Order> orderTable) {
        this.orderTable = orderTable;
    }

    public void save(Order order) {
        orderTable.putItem(order);
    }

    public Optional<Order> findById(String orderId) {
        Order order = orderTable.getItem(r -> r.key(k -> k.partitionValue(orderId)));
        return Optional.ofNullable(order);
    }

    public List<Order> findAll() {
        return orderTable.scan().items().stream().collect(Collectors.toList());
    }

    public void delete(String orderId) {
        orderTable.deleteItem(r -> r.key(k -> k.partitionValue(orderId)));
    }
}
