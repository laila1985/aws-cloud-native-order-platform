package com.example.orderplatform.controller;

import com.example.orderplatform.model.Order;
import com.example.orderplatform.exception.CustomerNotFoundException;
import com.example.orderplatform.exception.OrderNotFoundException;
import com.example.orderplatform.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @Test
    @DisplayName("POST /api/orders returns 201 with the created order")
    void create_returnsCreated() throws Exception {
        Order created = new Order();
        created.setOrderId("id-1");
        created.setCustomerId("cust-1");
        created.setStatus("CREATED");
        when(orderService.create(any(Order.class))).thenReturn(created);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"cust-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value("id-1"))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    @DisplayName("GET /api/orders/{id} returns 200 with the order")
    void get_returnsOrder() throws Exception {
        Order order = new Order();
        order.setOrderId("id-1");
        order.setCustomerId("cust-1");
        when(orderService.findById("id-1")).thenReturn(order);

        mockMvc.perform(get("/api/orders/id-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("id-1"))
                .andExpect(jsonPath("$.customerId").value("cust-1"));
    }

    @Test
    @DisplayName("GET /api/orders/{id} returns 404 when not found")
    void get_returnsNotFound() throws Exception {
        when(orderService.findById("missing"))
                .thenThrow(new OrderNotFoundException("missing"));

        mockMvc.perform(get("/api/orders/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Order not found: missing"));
    }

    @Test
    @DisplayName("GET /api/orders returns 200 with the list")
    void list_returnsOrders() throws Exception {
        Order order = new Order();
        order.setOrderId("id-1");
        when(orderService.findAll()).thenReturn(List.of(order));

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value("id-1"));
    }

    @Test
    @DisplayName("PUT /api/orders/{id} returns 200 with the updated order")
    void update_returnsUpdated() throws Exception {
        Order updated = new Order();
        updated.setOrderId("id-1");
        updated.setStatus("SHIPPED");
        when(orderService.update(org.mockito.ArgumentMatchers.eq("id-1"), any(Order.class)))
                .thenReturn(updated);

        mockMvc.perform(put("/api/orders/id-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SHIPPED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));
    }

    @Test
    @DisplayName("DELETE /api/orders/{id} returns 204")
    void delete_returnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/orders/id-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("POST /api/orders returns 404 when the customer does not exist")
    void create_returnsNotFoundWhenCustomerMissing() throws Exception {
        when(orderService.create(any(Order.class)))
                .thenThrow(new CustomerNotFoundException("ghost"));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"ghost\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Customer not found: ghost"));
    }
}
