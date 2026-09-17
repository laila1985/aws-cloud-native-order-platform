package com.example.orderplatform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI (Swagger) metadata for the order platform.
 * The interactive Swagger UI is available at /swagger-ui.html.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI orderPlatformOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Order Platform API")
                        .description("AWS Cloud Native Order Platform - Spring Boot + REST API + DynamoDB")
                        .version("v1.0.0")
                        .contact(new Contact().name("Order Platform Team"))
                        .license(new License().name("Apache 2.0")));
    }
}
