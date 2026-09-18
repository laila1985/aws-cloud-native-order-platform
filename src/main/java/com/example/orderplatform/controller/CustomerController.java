package com.example.orderplatform.controller;

import com.example.orderplatform.model.Customer;
import com.example.orderplatform.exception.CustomerNotFoundException;
import com.example.orderplatform.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

/**
 * REST endpoints for managing customers.
 */
@RestController
@RequestMapping("/api/customers")
@Tag(name = "Customers", description = "Create and read customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    @Operation(summary = "Create a customer")
    public ResponseEntity<Customer> create(@RequestBody Customer customer) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customerService.create(customer));
    }

    @GetMapping("/{customerId}")
    @Operation(summary = "Get a customer by id")
    public ResponseEntity<Customer> get(@PathVariable String customerId) {
        return ResponseEntity.ok(customerService.findById(customerId));
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(CustomerNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Collections.singletonMap("error", e.getMessage()));
    }
}
