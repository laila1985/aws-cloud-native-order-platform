# aws-cloud-native-order-platform

A step-by-step cloud-native order platform.

Architecture:

```
Spring Boot
    ↓
REST API  (orders + customers)
    ↓
DynamoDB  (Orders + Customers tables)
    ↓
SNS fan-out → SQS consumers (processor / lambda / email / sms / shipping)
```

## Asynchronous processing (Step 3)

After an order is created, the API publishes an event that fans out through
AWS messaging:

```
                    ┌── SQS ──► Order Processor   (marks order "PROCESSED")
                    │
                    ├── SQS ──► Lambda-like       (marks order "NOTIFIED")
                    │
Order API ──► SNS ──┼── SQS ──► Email handler    (SES confirmation email)
   (create)         │
                    ├── SQS ──► SMS handler       (SNS text message)
                    │
                    └── SQS ──► Shipping processor (marks order "SHIPPING")
```

- **SNS topic** (`order-events`) fans out to **five** subscribers.
- **SQS queue** (`order-processor-queue`) → the in-app `OrderProcessor`, an
  `@Scheduled` poller that marks the order `PROCESSED`.
- **Lambda** — for local development this is emulated as a second SQS queue
  (`order-lambda-queue`) consumed by `OrderLambdaHandler` (marks the order
  `NOTIFIED`). In AWS, replace this with a real Lambda subscribed to the topic
  (`protocol = "lambda"`); the queue + poller then become unnecessary.
- **Email** — `EmailNotificationHandler` consumes `order-email-queue`, looks up
  the customer, and sends a confirmation email through **SES**.
- **SMS** — `SmsNotificationHandler` consumes `order-sms-queue`, looks up the
  customer, and sends a text message via **SNS** direct publish to the
  customer's phone number.
- **Shipping** — `ShippingProcessor` consumes `order-shipping-queue` and marks
  the order `SHIPPING`.

Publishing is **best-effort** and non-blocking: it never fails the order
creation response, and the app still boots even if messaging resources are
unavailable.

### Local emulation (LocalStack)

`docker-compose.yml` starts **LocalStack** (`sns` + `sqs` + `ses` services)
alongside DynamoDB Local. The app points at LocalStack via `aws.sns.endpoint` /
`aws.sqs.endpoint` / `aws.ses.endpoint` (default `http://localhost:4566`), the
same pattern used for DynamoDB. Resources (topic, queues, subscriptions) are
provisioned idempotently on startup.

> LocalStack records SES/SNS calls but does not actually deliver real email or
> SMS — that only happens against real AWS.

## Security (Step 4)

Security is defined as infrastructure-as-code (CloudFormation) — IAM roles,
Secrets Manager, and KMS. See [`docs/GUIDE.md`](docs/GUIDE.md) — "Step 4".

## Redis caching (Step 5)

`GET /orders/{id}` is cached in **Redis** (ElastiCache in AWS) using the
cache-aside pattern. See [`docs/GUIDE.md`](docs/GUIDE.md) — "Step 5".

## Documentation

- **Complete guide** (what it is + how it was built, line by line): [`docs/GUIDE.md`](docs/GUIDE.md)

## Stack

- Java 21
- Spring Boot 3.3.x
- AWS SDK v2 (DynamoDB Enhanced Client, SNS, SQS, SES)
- DynamoDB Local (via Docker) for development
- LocalStack (SNS + SQS + SES) for local messaging emulation
- Redis (cache-aside for reads; ElastiCache in AWS)

## Prerequisites

- JDK 21
- Gradle (or use the included wrapper)
- Docker (for local DynamoDB, LocalStack, and Redis)

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
| POST   | `/api/customers`      | Create a customer      |
| GET    | `/api/customers/{id}` | Get a customer by id   |
| POST   | `/api/orders`         | Create an order        |
| GET    | `/api/orders/{id}`    | Get an order by id     |
| GET    | `/api/orders`         | List all orders        |
| PUT    | `/api/orders/{id}`    | Update an order        |
| DELETE | `/api/orders/{id}`    | Delete an order        |

> An order must reference an existing customer: creating an order with a missing
> or unknown `customerId` returns `404`. The order's `customerId` is immutable
> after creation — attempts to change it return `400`.

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
- `aws.dynamodb.tableName` — orders table name (default `Orders`)
- `aws.dynamodb.customerTableName` — customers table name (default `Customers`)
- `aws.sns.endpoint` / `aws.sqs.endpoint` / `aws.ses.endpoint` — messaging endpoints (LocalStack)
- `aws.sns.topicName` — SNS topic name (default `order-events`)
- `aws.sqs.processorQueueName` / `lambdaQueueName` / `emailQueueName` / `smsQueueName` / `shippingQueueName` — the five fan-out queues
- `aws.ses.senderEmail` — "from" address for SES emails
- `aws.currency` — currency used in order confirmation emails (default `AED`)
- `aws.accessKeyId` / `aws.secretAccessKey` — static credentials (used only for local dev)

When running in AWS, leave the endpoint and credentials empty and rely on the
default credential chain (instance role, environment, etc.).
