package com.example.orderplatform.integration;

import com.example.orderplatform.model.Order;
import com.example.orderplatform.model.OrderItem;
import com.example.orderplatform.repository.OrderRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link OrderRepository} against a real DynamoDB Local
 * instance (Testcontainers). Verifies the enhanced client CRUD operations.
 */
class OrderRepositoryIntegrationTest extends AbstractDynamoDbIntegrationTest {

    private static final String TABLE_NAME = "Orders";

    private static OrderRepository repository;

    @BeforeAll
    static void setUp() {
        DynamoDbClient client = DynamoDbClient.builder()
                .endpointOverride(URI.create(dynamoDbEndpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local", "local")))
                .build();

        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
                .dynamoDbClient(client)
                .build();

        DynamoDbTable<Order> table = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(Order.class));
        table.createTable(r -> r.provisionedThroughput(p ->
                p.readCapacityUnits(5L).writeCapacityUnits(5L)));

        repository = new OrderRepository(table);
    }

    private Order order(String id, String customerId) {
        Order order = new Order();
        order.setOrderId(id);
        order.setCustomerId(customerId);
        order.setStatus("CREATED");

        OrderItem item = new OrderItem();
        item.setProductId("p-1");
        item.setProductName("Laptop");
        item.setQuantity(2);
        item.setUnitPrice(new BigDecimal("10.50"));
        order.setItems(List.of(item));
        order.setTotalAmount(new BigDecimal("21.00"));
        return order;
    }

    @Test
    @DisplayName("save then findById round-trips an order")
    void saveAndFindById() {
        repository.save(order("int-1", "cust-1"));

        Optional<Order> found = repository.findById("int-1");

        assertThat(found).isPresent();
        assertThat(found.get().getCustomerId()).isEqualTo("cust-1");
        assertThat(found.get().getItems()).hasSize(1);
        assertThat(found.get().getTotalAmount()).isEqualByComparingTo("21.00");
    }

    @Test
    @DisplayName("findById returns empty for an unknown order")
    void findById_returnsEmptyWhenMissing() {
        assertThat(repository.findById("does-not-exist")).isEmpty();
    }

    @Test
    @DisplayName("findAll returns all saved orders")
    void findAll_returnsAllOrders() {
        repository.save(order("int-2", "cust-2"));
        repository.save(order("int-3", "cust-3"));

        List<Order> all = repository.findAll();

        assertThat(all).extracting(Order::getOrderId)
                .contains("int-2", "int-3");
    }

    @Test
    @DisplayName("delete removes an order")
    void delete_removesOrder() {
        repository.save(order("int-4", "cust-4"));
        assertThat(repository.findById("int-4")).isPresent();

        repository.delete("int-4");

        assertThat(repository.findById("int-4")).isEmpty();
    }
}
