package com.example.orderplatform.service;

import com.example.orderplatform.exception.CustomerNotFoundException;
import com.example.orderplatform.model.Customer;
import com.example.orderplatform.repository.CustomerRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Business logic for customers.
 */
@Service
public class CustomerService {

    private final CustomerRepository customerRepository;

    public CustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public Customer create(Customer customer) {
        if (customer.getCustomerId() == null || customer.getCustomerId().isBlank()) {
            customer.setCustomerId(UUID.randomUUID().toString());
        }
        customerRepository.save(customer);
        return customer;
    }

    public Customer findById(String customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException(customerId));
    }
}
