# aws-cloud-native-order-platform

A step-by-step cloud-native order platform.

Architecture:

```
Spring Boot
    ↓
REST API
    ↓
DynamoDB
```

## Stack

- Java 21
- Spring Boot 3.3.x
- AWS SDK v2 (DynamoDB Enhanced Client)
- DynamoDB Local (via Docker) for development

## Prerequisites

- JDK 21
- Gradle (or use the included wrapper)
- Docker (for local DynamoDB)

## Run locally

1. Start DynamoDB Local:

   ```bash
   docker compose up -d
   ```

2. Build & run the application:

   ```bash
   ./gradlew bootRun          # Linux / macOS
   gradlew.bat bootRun        # Windows
   ```

   The app listens on `http://localhost:8080` and talks to DynamoDB Local at
   `http://localhost:8000`.

   Other useful Gradle tasks:

   ```bash
   ./gradlew clean build      # compile + package the boot jar
   ./gradlew bootJar          # build the executable jar only
   ./gradlew test             # run tests
   ```

## Tests

### Unit tests

Fast, no external dependencies (no Docker needed):

```bash
./gradlew test --tests "*Test"    # Linux / macOS
gradlew.bat test --tests "*Test"  # Windows
```

Covers the service (business logic, Mockito) and the controller (`@WebMvcTest`
slice with a mocked service).

### Integration tests

Full-stack tests against a **real DynamoDB Local** started via Testcontainers:

- `OrderRepositoryIntegrationTest` — enhanced client CRUD against DynamoDB.
- `OrderApiIntegrationTest` — full HTTP flow (create → get → update → list →
  delete) through the Spring context and MockMvc.

```bash
./gradlew test --tests "*IntegrationTest"    # Linux / macOS
gradlew.bat test --tests "*IntegrationTest"  # Windows
```

**Requirements**: Docker must be running and reachable by Testcontainers.
If Docker is unavailable, the integration tests are **skipped** (not failed),
so the build remains green.

> **Note for Docker Desktop on Windows**: Testcontainers uses the `docker-java`
> client, which may fail to reach newer Docker Desktop versions through the
> Windows named-pipe proxy (it receives a `400 BadRequest` with empty daemon
> info even though the `docker` CLI works). If you see "Could not find a valid
> Docker environment", enable Docker Desktop's legacy TCP endpoint:
>
> **Docker Desktop → Settings → General → "Expose daemon on tcp://localhost:2375
> without TLS"**, then set `DOCKER_HOST=tcp://localhost:2375` before running the
> tests. (Enable TLS or restrict to localhost if you do this on a shared machine.)

## Swagger UI

The application exposes an interactive Swagger UI powered by springdoc-openapi:

- **Swagger UI**: http://localhost:8080/swagger-ui/index.html
- **OpenAPI JSON**: http://localhost:8080/v3/api-docs

Open the Swagger UI to explore and invoke the REST endpoints directly from
the browser.

## REST API

| Method | Endpoint              | Description            |
|--------|-----------------------|------------------------|
| POST   | `/api/orders`         | Create an order        |
| GET    | `/api/orders/{id}`    | Get an order by id     |
| GET    | `/api/orders`         | List all orders        |
| PUT    | `/api/orders/{id}`    | Update an order        |
| DELETE | `/api/orders/{id}`    | Delete an order        |

### Example: create an order

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "cust-123",
    "items": [
      { "productId": "p-1", "productName": "Laptop", "quantity": 1, "unitPrice": 1200.00 },
      { "productId": "p-2", "productName": "Mouse", "quantity": 2, "unitPrice": 25.50 }
    ]
  }'
```

## Configuration

Configuration is in `src/main/resources/application.yml`. Key properties:

- `aws.region` — AWS region (default `us-east-1`)
- `aws.dynamodb.endpoint` — DynamoDB endpoint; set to a local URL for local dev, empty for real AWS
- `aws.dynamodb.tableName` — table name (default `Orders`)
- `aws.accessKeyId` / `aws.secretAccessKey` — static credentials (used only for local dev)

When running in AWS, leave the endpoint and credentials empty and rely on the
default credential chain (instance role, environment, etc.).
