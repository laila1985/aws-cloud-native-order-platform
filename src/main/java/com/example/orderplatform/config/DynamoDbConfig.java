package com.example.orderplatform.config;

import com.example.orderplatform.model.Customer;
import com.example.orderplatform.model.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

import java.net.URI;

/**
 * Configures the AWS SDK v2 DynamoDB (enhanced) client.
 * <p>
 * When running locally, point {@code aws.dynamodb.endpoint} at a local
 * DynamoDB instance (e.g. the one started by docker-compose). When running in
 * AWS, leave the endpoint empty and rely on the default credential chain.
 */
@Configuration
public class DynamoDbConfig {

    @Value("${aws.region:us-east-1}")
    private String region;

    @Value("${aws.dynamodb.endpoint:}")
    private String endpoint;

    @Value("${aws.dynamodb.tableName:Orders}")
    private String tableName;

    @Value("${aws.dynamodb.customerTableName:Customers}")
    private String customerTableName;

    @Value("${aws.accessKeyId:}")
    private String accessKeyId;

    @Value("${aws.secretAccessKey:}")
    private String secretAccessKey;

    @Bean
    public DynamoDbClient dynamoDbClient() {
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(region));

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }

        if (accessKeyId != null && !accessKeyId.isBlank()) {
            AwsCredentialsProvider credentials = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey));
            builder.credentialsProvider(credentials);
        }

        return builder.build();
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
    }

    @Bean
    public DynamoDbTable<Order> orderTable(DynamoDbEnhancedClient enhancedClient) {
        DynamoDbTable<Order> table = enhancedClient.table(tableName, TableSchema.fromBean(Order.class));
        ensureTableExists(table);
        return table;
    }

    @Bean
    public DynamoDbTable<Customer> customerTable(DynamoDbEnhancedClient enhancedClient) {
        DynamoDbTable<Customer> table = enhancedClient.table(customerTableName, TableSchema.fromBean(Customer.class));
        ensureTableExists(table);
        return table;
    }

    /**
     * Creates the table if it does not already exist. This is convenient for
     * local development; in production use IaC (CloudFormation/Terraform).
     */
    private void ensureTableExists(DynamoDbTable<?> table) {
        try {
            table.describeTable();
        } catch (ResourceNotFoundException e) {
            table.createTable(r -> r
                    .provisionedThroughput(p -> p.readCapacityUnits(5L).writeCapacityUnits(5L)));
        }
    }
}
