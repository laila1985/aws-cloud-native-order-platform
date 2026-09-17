# Order Platform — Technical Specification & Beginner's Guide

> A step-by-step, plain-English explanation of what this application does, how
> it is built, and how the pieces fit together. Written for someone new to
> Spring Boot, AWS, and cloud-native development.

---

## 1. What does this application do?

This is a **cloud-native order management platform**. In plain terms: it is a
backend web service that lets you **create, read, update, and delete orders**
(the kind of thing you'd use to track customer purchases).

A customer order looks like this:

- **Who** placed it — a `customerId`
- **What** they bought — a list of `items` (each with a product, quantity, price)
- **How much** it costs — a `totalAmount` (calculated automatically)
- **What state** it is in — a `status` (e.g. `CREATED`, `PROCESSED`, `NOTIFIED`)
- **When** it was created — a `createdAt` timestamp

The application stores orders in a database and exposes an HTTP API so other
programs (or people using tools like Swagger) can work with them.

---

## 2. The big picture (architecture)

The application is built in layers. Data flows top-to-bottom like this:

```
        ┌──────────────────────────────┐
        │      REST API (Controller)   │   ← the "front door" (HTTP)
        └──────────────┬───────────────┘
                       │
        ┌──────────────▼───────────────┐
        │      Service (business logic) │   ← the "brain" (rules)
        └──────────────┬───────────────┘
                       │
        ┌──────────────▼───────────────┐
        │   Repository (data access)    │   ← the "librarian" (DB)
        └──────────────┬───────────────┘
                       │
        ┌──────────────▼───────────────┐
        │         DynamoDB              │   ← the "filing cabinet" (storage)
        └──────────────────────────────┘
```

**Analogy:** imagine a restaurant.

- The **Controller** is the waiter — takes your order at the table (HTTP request)
  and brings back the result (HTTP response).
- The **Service** is the chef — applies the rules (calculate the total, assign a
  status, decide what to do).
- The **Repository** is the kitchen's storage manager — knows exactly where to
  put things and how to find them.
- **DynamoDB** is the pantry/filing cabinet — the physical place where data is
  actually stored.

This separation is called **layered architecture**, and it keeps each part
focused on one job, which makes the code easier to understand, test, and change.

---

## 3. Technology stack

| Technology | What it is | Why we use it |
|------------|-----------|---------------|
| **Java 21** | Programming language | Modern, widely-used, runs the app |
| **Spring Boot 3.3** | Web framework | Handles HTTP, configuration, and "wiring" the layers together |
| **AWS SDK v2** | Library to talk to AWS | Lets Java code use DynamoDB, SNS, and SQS |
| **DynamoDB** | NoSQL database (AWS) | Stores orders — fast, scalable, serverless |
| **SNS** | Pub/sub messaging (AWS) | Fans out order events to multiple subscribers |
| **SQS** | Queue messaging (AWS) | Buffers messages for asynchronous processing |
| **Gradle** | Build tool | Compiles and packages the app |
| **Swagger/OpenAPI** | API documentation | Interactive UI to explore and test the API |
| **Docker** | Container runtime | Runs local emulators (DynamoDB Local, LocalStack) |
| **Testcontainers / JUnit** | Testing | Unit & integration tests |

---

## 4. The data model (what we store)

There are two "objects" (Java classes) that describe our data:

### 4.1 `Order` (the main entity)

An order is the top-level record. It has:

| Field | Type | Meaning |
|-------|------|---------|
| `orderId` | String | Unique identifier (the **partition key** in DynamoDB) |
| `customerId` | String | Who placed the order |
| `status` | String | Lifecycle state (`CREATED` → `PROCESSED` → `NOTIFIED`) |
| `items` | List of `OrderItem` | The products purchased |
| `totalAmount` | BigDecimal | Total cost (calculated, not typed by user) |
| `createdAt` | Instant | When the order was created |

### 4.2 `OrderItem` (a line in the order)

| Field | Type | Meaning |
|-------|------|---------|
| `productId` | String | Product identifier |
| `productName` | String | Human-readable name |
| `quantity` | Integer | How many |
| `unitPrice` | BigDecimal | Price of one unit |

**DynamoDB table:** `Orders`, with **partition key** `orderId`.

> A *partition key* is DynamoDB's way of uniquely identifying and physically
> distributing a record. Think of it as the "primary key" in a traditional
> database.

---

## 5. The REST API (how outsiders talk to us)

The API follows **REST** conventions: HTTP methods map to actions on resources.

| Method | Endpoint | What it does | Success code |
|--------|----------|--------------|--------------|
| `POST` | `/api/orders` | Create a new order | `201 Created` |
| `GET` | `/api/orders/{orderId}` | Fetch one order | `200 OK` |
| `GET` | `/api/orders` | List all orders | `200 OK` |
| `PUT` | `/api/orders/{orderId}` | Update an order | `200 OK` |
| `DELETE` | `/api/orders/{orderId}` | Delete an order | `204 No Content` |

If you request an order that doesn't exist, the API returns **`404 Not Found`**
with a helpful error message like `{"error": "Order not found: 123"}`.

**Example** — creating an order (this is the JSON you'd send to `POST /api/orders`):

```json
{
  "customerId": "cust-123",
  "items": [
    { "productId": "p-1", "productName": "Laptop", "quantity": 1, "unitPrice": 1200.00 },
    { "productId": "p-2", "productName": "Mouse",  "quantity": 2, "unitPrice": 25.50 }
  ]
}
```

The service automatically:

1. Generates an `orderId` (a UUID) if you didn't provide one.
2. Sets `status` to `CREATED`.
3. Sets `createdAt` to "now".
4. **Calculates** `totalAmount` = `(1 × 1200.00) + (2 × 25.50)` = **`1251.00`**.

---

## 6. Business logic (what the Service does)

The `OrderService` contains the rules. These are the important behaviors:

### 6.1 Create an order
- Fills in missing fields (id, status, timestamp).
- Computes the total by summing `unitPrice × quantity` for every item.
- Saves it to DynamoDB.
- Publishes an event (see §7).

### 6.2 Update an order
- Loads the existing order (throws `404` if missing).
- Replaces only the fields that were provided.
- Recalculates the total if the items changed.

### 6.3 Delete an order
- Verifies the order exists (throws `404` if not).
- Removes it from DynamoDB.

### 6.4 Calculate the total
A private helper multiplies each item's price by its quantity and sums them,
returning `0` if there are no items.

---

## 7. Asynchronous processing (SNS + SQS + Lambda)

This is the "cloud-native" part. When an order is created, we don't just save it
and stop — we **announce** it, and other parts of the system react to that
announcement *in the background* (asynchronously).

```
                    ┌── SQS ──► Order Processor  → marks order "PROCESSED"
                    │
Order API ──► SNS ──┤
   (create)         │
                    └── Lambda ────────────────→ marks order "NOTIFIED"
```

### The concepts, explained simply

- **SNS** (Simple Notification Service) is like a **megaphone** or a **newsletter
  list**. You publish a message once, and everyone subscribed receives a copy.
  This is called **publish/subscribe** ("pub/sub").
- **SQS** (Simple Queue Service) is like a **mailbox queue**. Messages wait in
  line until a worker comes to process them.
- **Lambda** is AWS's **serverless function** — code that runs on demand
  without you managing a server.

### The flow, step by step

1. The order is created and saved (status = `CREATED`).
2. The app publishes an `OrderCreatedEvent` to the SNS topic `order-events`.
3. SNS fans the event out to two subscribers:
   - an **SQS queue** (`order-processor-queue`) → the `OrderProcessor` poller
     picks it up and marks the order **`PROCESSED`**.
   - a **Lambda-like consumer** → marks the order **`NOTIFIED`**.
4. Each consumer deletes its message from the queue when done.

**Important design choices:**

- **Best-effort / non-blocking** — publishing the event never fails or delays
  the HTTP response. If messaging is down, the order is still created.
- **Resilient startup** — if LocalStack isn't running, the app still boots; it
  just skips messaging until resources are available.

### About the "Lambda" in this project

For **local development**, the Lambda is emulated as a **second SQS queue**
(`order-lambda-queue`) polled by `OrderLambdaHandler` (which marks orders
`NOTIFIED`). In **production AWS**, you'd replace that queue + poller with a real
Lambda function subscribed to the SNS topic (using `protocol = "lambda"`). The
code is structured so that swap is straightforward.

---

## 8. Configuration (application.yml)

Key settings live in `src/main/resources/application.yml`:

```yaml
server:
  port: 8080                     # the app listens here

aws:
  region: us-east-1
  dynamodb:
    endpoint: http://localhost:8000   # local DynamoDB Local
    tableName: Orders
  sns:
    endpoint: http://localhost:4566   # local LocalStack
    topicName: order-events
  sqs:
    endpoint: http://localhost:4566   # local LocalStack
    processorQueueName: order-processor-queue
    lambdaQueueName: order-lambda-queue
  accessKeyId: local                 # dummy credentials for local dev
  secretAccessKey: local
```

> **Local vs AWS:** when running locally, `endpoint` points at local emulators
> (DynamoDB Local on port 8000, LocalStack on port 4566) with dummy credentials.
> In real AWS, you'd leave the `endpoint` empty and the SDK uses your real
> credentials automatically.

---

## 9. Local infrastructure (docker-compose.yml)

Running the full app locally requires two emulated AWS services, both started
with Docker:

| Service | Image | Purpose | Port |
|---------|-------|---------|------|
| `dynamodb-local` | `amazon/dynamodb-local` | Emulates DynamoDB | 8000 |
| `localstack` | `localstack/localstack` | Emulates SNS + SQS | 4566 |

Start them with:

```bash
docker compose up -d
```

---

## 10. How to run the application

```bash
# 1. Start the local AWS emulators
docker compose up -d

# 2. Build & run the app (Windows: gradlew.bat, Linux/macOS: ./gradlew)
gradlew.bat bootRun
```

Then open the **Swagger UI** to interact with the API in your browser:

- Swagger UI: http://localhost:8080/swagger-ui/index.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

---

## 11. Testing strategy

| Test type | What it verifies | Needs Docker? |
|-----------|------------------|---------------|
| **Unit tests** (`OrderServiceTest`, `OrderControllerTest`, `OrderEventPublisherTest`, `OrderProcessorTest`) | Individual classes in isolation (using Mockito to fake dependencies) | No |
| **Integration tests** (`OrderRepositoryIntegrationTest`, `OrderApiIntegrationTest`) | Real components together against a real DynamoDB (via Testcontainers) | Yes |

Run tests:

```bash
gradlew.bat test                          # all tests
gradlew.bat test --tests "*Test"          # unit tests only
gradlew.bat test --tests "*IntegrationTest"  # integration tests (needs Docker)
```

---

## 12. Glossary (for beginners)

| Term | Plain-English meaning |
|------|----------------------|
| **REST API** | A way for programs to talk over HTTP using standard verbs (GET, POST, PUT, DELETE) |
| **Endpoint** | A specific URL + method that performs one action |
| **Bean** | In Spring, an object managed and wired together automatically by the framework |
| **Dependency Injection** | Spring hands objects the things they need (via constructors) instead of objects creating them |
| **NoSQL / DynamoDB** | A database that stores flexible records keyed by an ID, rather than rigid tables |
| **Partition key** | The unique ID that identifies and distributes a DynamoDB record |
| **Pub/sub (SNS)** | One publisher → many subscribers (fan-out) |
| **Queue (SQS)** | Messages wait in line for a worker to process them |
| **Lambda** | Serverless function that runs on demand in AWS |
| **Asynchronous** | Work happens in the background, without blocking the main flow |
| **Container (Docker)** | A lightweight, portable bundle that runs an app/service consistently |
| **Emulator (LocalStack)** | A local stand-in for a real AWS service during development |
| **IAM** | AWS's permission system — decides who can do what to which resources |
| **Least privilege** | Giving each component only the minimum permissions it needs |
| **KMS** | AWS's key management service for encrypting/decrypting data |

---

## 13. Summary — what you should take away

1. This is a **Spring Boot** app that manages orders via a **REST API**.
2. Orders are stored in **DynamoDB** (a NoSQL database).
3. The code is split into **Controller → Service → Repository** layers.
4. Creating an order triggers an **asynchronous fan-out** through **SNS → SQS /
   Lambda**, which updates the order's status in the background.
5. Everything runs **locally** using Docker emulators (DynamoDB Local +
   LocalStack), making it easy to develop and test without real AWS.
6. It has a full **test suite** (unit + integration) and **Swagger UI** for
   exploring the API.
7. Security is defined as **infrastructure-as-code** (CloudFormation) covering
   **IAM**, **Secrets Manager**, and **KMS** (see Step 4, below).

---

## 14. Step 4 — Security (IAM + Secrets Manager + KMS)

Security is modeled as infrastructure-as-code in
`src/main/resources/cloudformation/security.yaml` (deployment steps are below).

```
IAM
 ├── Spring Boot permissions   (DynamoDB + SNS/SQS + Secrets + KMS)
 ├── Lambda permissions        (DynamoDB update + KMS decrypt)
 └── EKS permissions           (cluster + worker node roles)

Secrets Manager
 └── application secrets       (auto-generated username + strong password)

KMS
 └── encryption keys           (customer-managed key with rotation)
```

### The concepts, explained simply

- **IAM** (Identity and Access Management) is AWS's **permission system**. It
  answers "who is allowed to do what to which resources?". Each component
  (Spring Boot app, Lambda, EKS) gets its own **role** with the smallest set of
  permissions it actually needs — this is called **least privilege**.
- **Secrets Manager** safely stores **passwords, API keys, and tokens**. Instead
  of hard-coding secrets in code, the app fetches them at runtime. It can also
  auto-generate and rotate strong passwords.
- **KMS** (Key Management Service) manages the **encryption keys** used to
  scramble data so only authorized parties can read it. The secret is encrypted
  with our own KMS key.

### Why it matters

- **Least-privilege IAM** limits the blast radius if one component is
  compromised — the Spring Boot app can only touch the `Orders` table, not any
  other table or service.
- **Secrets Manager** removes hard-coded credentials from code (a common
  security anti-pattern).
- **KMS** encrypts secrets and (optionally) data at rest, with automatic key
  rotation.

### What the template creates

| Resource | Type | Purpose |
|----------|------|---------|
| `OrdersEncryptionKey` (+ alias) | KMS | Encryption key with rotation |
| `ApplicationSecret` | Secrets Manager | Auto-generated `{username, password}` JSON |
| `SpringBootAppRole` | IAM | Least-privilege role for the app |
| `LambdaConsumerRole` | IAM | Role for the Lambda consumer |
| `EksClusterRole` / `EksNodeRole` | IAM | Roles for EKS cluster + nodes |

### Deploy it

**Prerequisites**: AWS CLI installed and configured (`aws configure`), plus IAM
permissions to create roles, KMS keys, and secrets.

```bash
aws cloudformation create-stack \
  --stack-name order-platform-security \
  --template-body file://src/main/resources/cloudformation/security.yaml \
  --parameters ParameterKey=Stage,ParameterValue=dev \
  --capabilities CAPABILITY_NAMED_IAM
```

> `CAPABILITY_NAMED_IAM` is required because the stack creates IAM roles with
> explicit names.

**Useful commands:**

```bash
# Watch the stack create
aws cloudformation describe-stacks --stack-name order-platform-security

# Get the resource ARNs (for wiring into the app / Lambda / EKS)
aws cloudformation describe-stacks --stack-name order-platform-security \
  --query "Stacks[0].Outputs"

# Read the generated secret value
aws secretsmanager get-secret-value \
  --secret-id order-platform/dev/application \
  --query SecretString --output text

# Delete the stack when done
aws cloudformation delete-stack --stack-name order-platform-security
```

### IAM — Spring Boot app permissions (detail)

The app role (`order-platform-app-dev`) is granted **least-privilege** access:

- **DynamoDB**: read/write on the `Orders` table only.
- **SNS**: `sns:Publish` on the `order-events` topic only.
- **SQS**: receive/delete on the two queues only.
- **Secrets Manager**: `GetSecretValue` on the one secret only.
- **KMS**: `Decrypt`/`GenerateDataKey` on our key only.

### IAM — Lambda permissions (detail)

The Lambda role (`order-platform-lambda-dev`) gets:

- **DynamoDB**: `GetItem`/`UpdateItem` on `Orders` (to mark orders `NOTIFIED`).
- **KMS**: `Decrypt` (to read secrets).
- Plus the standard `AWSLambdaBasicExecutionRole` for CloudWatch Logs.

### IAM — EKS permissions (detail)

Two roles:

- **Cluster role** (`order-platform-eks-cluster-dev`) with `AmazonEKSClusterPolicy`.
- **Node role** (`order-platform-eks-node-dev`) with worker-node, CNI, and ECR
  read policies.

### Note on EKS pod access (IRSA)

To let a pod running in EKS assume the app role, enable **IAM Roles for Service
Accounts (IRSA)**. The template includes a commented-out
`sts:AssumeRoleWithWebIdentity` trust statement — fill in your cluster's OIDC
provider ARN and uncomment it.




