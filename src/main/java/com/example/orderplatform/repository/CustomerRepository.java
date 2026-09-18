package com.example.orderplatform.repository;

import com.example.orderplatform.model.Customer;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

import java.util.Optional;

/**
 * Data access layer for {@link Customer} backed by the DynamoDB Enhanced Client.
 */
@Repository
public class CustomerRepository {

    private final DynamoDbTable<Customer> customerTable;

    public CustomerRepository(DynamoDbTable<Customer> customerTable) {
        this.customerTable = customerTable;
    }

    public void save(Customer customer) {
        customerTable.putItem(customer);
    }

    public Optional<Customer> findById(String customerId) {
        Customer customer = customerTable.getItem(r -> r.key(k -> k.partitionValue(customerId)));
        return Optional.ofNullable(customer);
    }
}
