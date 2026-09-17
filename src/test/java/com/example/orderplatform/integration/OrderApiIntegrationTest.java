package com.example.orderplatform.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack integration test: Spring context + REST controller + service +
 * repository against a real DynamoDB Local (Testcontainers).
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiIntegrationTest extends AbstractDynamoDbIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void dynamoDbProperties(DynamicPropertyRegistry registry) {
        registry.add("aws.dynamodb.endpoint", AbstractDynamoDbIntegrationTest::dynamoDbEndpoint);
        registry.add("aws.dynamodb.tableName", () -> "Orders");
        registry.add("aws.accessKeyId", () -> "local");
        registry.add("aws.secretAccessKey", () -> "local");
        registry.add("aws.region", () -> "us-east-1");
    }

    @Test
    @DisplayName("full CRUD flow: create -> get -> update -> list -> delete")
    void fullCrudFlow() throws Exception {
        String createBody = """
                {
                  "customerId": "cust-1",
                  "items": [
                    { "productId": "p-1", "productName": "Laptop", "quantity": 2, "unitPrice": 10.50 }
                  ]
                }
                """;

        // Create
        String createResponse = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.totalAmount").value(21.00))
                .andReturn().getResponse().getContentAsString();

        String orderId = com.jayway.jsonpath.JsonPath.read(createResponse, "$.orderId");

        // Get by id
        mockMvc.perform(get("/api/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.customerId").value("cust-1"));

        // Update
        mockMvc.perform(put("/api/orders/" + orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SHIPPED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));

        // List
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value(orderId));

        // Delete
        mockMvc.perform(delete("/api/orders/" + orderId))
                .andExpect(status().isNoContent());

        // Get after delete -> 404
        mockMvc.perform(get("/api/orders/" + orderId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET unknown order returns 404 with error body")
    void getUnknownOrderReturns404() throws Exception {
        mockMvc.perform(get("/api/orders/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Order not found: unknown"));
    }
}
