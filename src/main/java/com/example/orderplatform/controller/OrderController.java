package com.example.orderplatform.controller;

import com.example.orderplatform.model.Order;
import com.example.orderplatform.exception.OrderNotFoundException;
import com.example.orderplatform.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for managing orders.
 */
@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Create, read, update and delete orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @Operation(summary = "Create an order", description = "Creates a new order. If orderId is omitted it is auto-generated.")
    @ApiResponse(responseCode = "201", description = "Order created")
    public ResponseEntity<Order> create(@RequestBody Order order) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.create(order));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get an order by id")
    @ApiResponse(responseCode = "200", description = "Order found")
    @ApiResponse(responseCode = "404", description = "Order not found")
    public ResponseEntity<Order> get(
            @Parameter(description = "Order identifier") @PathVariable String orderId) {
        return ResponseEntity.ok(orderService.findById(orderId));
    }

    @GetMapping
    @Operation(summary = "List all orders")
    public ResponseEntity<List<Order>> list() {
        return ResponseEntity.ok(orderService.findAll());
    }

    @PutMapping("/{orderId}")
    @Operation(summary = "Update an order")
    @ApiResponse(responseCode = "200", description = "Order updated")
    @ApiResponse(responseCode = "404", description = "Order not found")
    public ResponseEntity<Order> update(
            @Parameter(description = "Order identifier") @PathVariable String orderId,
            @RequestBody Order order) {
        return ResponseEntity.ok(orderService.update(orderId, order));
    }

    @DeleteMapping("/{orderId}")
    @Operation(summary = "Delete an order")
    @ApiResponse(responseCode = "204", description = "Order deleted")
    @ApiResponse(responseCode = "404", description = "Order not found")
    public ResponseEntity<Void> delete(
            @Parameter(description = "Order identifier") @PathVariable String orderId) {
        orderService.delete(orderId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(OrderNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", e.getMessage()));
    }
}
