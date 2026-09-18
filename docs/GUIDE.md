# Order Platform — Complete Guide

> A single, comprehensive guide: **what** this application does, **how** it is
> organized, and **how it was built, line by line**. Written for a beginner who
> has never seen Spring Boot, AWS, or even much Java before.

---

## Table of contents

1. [Before you start](#before-you-start) — what we're building, tools you need
2. [The application at a glance](#the-application-at-a-glance) — stack, data model, API
3. [Step 1 — The Spring Boot skeleton](#step-1--the-spring-boot-skeleton)
4. [Step 2 — REST API + DynamoDB](#step-2--rest-api--dynamodb)
5. [Step 3 — Asynchronous processing (SNS + SQS + Lambda)](#step-3--asynchronous-processing-sns--sqs--lambda)
6. [Step 4 — Security (IAM + Secrets Manager + KMS)](#step-4--security-iam--secrets-manager--kms)
7. [Step 5 — Redis caching](#step-5--redis-caching)
8. [Testing strategy](#testing-strategy)
9. [How to run it](#how-to-run-it)
10. [Glossary](#glossary)
11. [The full picture at the end](#the-full-picture-at-the-end)

---

## Before you start

### What we're building

A **backend web service** that manages **customers** and **orders**. It can:

- Create and read customers, and create, read, update, and delete orders (REST API).
- Store them in **DynamoDB** (a NoSQL database on AWS) — two tables: `Customers`
  and `Orders`. An order must belong to an existing customer.
- Publish an event when an order is created, which fans out through **SNS** to
  **five** consumers (asynchronous processing): an order processor, a
  Lambda-like consumer, an email notification, an SMS notification, and a
  shipping processor.
- Secure the whole thing with **IAM**, **Secrets Manager**, and **KMS**.
- Cache reads in **Redis** so they are fast.

### Is this a "Spring Boot" app or an "AWS" app?

It's **both** — and the distinction matters:

- **Spring Boot** is the **application** itself: your Java code, the controllers,
  services, and repositories, plus the embedded web server. This runs anywhere.
- **AWS** is the **backing services** it depends on: DynamoDB (database), SNS/SQS
  (messaging), Redis/ElastiCache (caching), and IAM/KMS/Secrets Manager
  (security). These are *managed* by Amazon — you don't run them yourself.

```
Your code (Spring Boot, Java)     ← you write this; it runs anywhere
        │
        │  talks to AWS through the AWS SDK
        ▼
AWS services (DynamoDB, SNS…)     ← managed by Amazon; you only configure them
```

"Cloud-native" means the app is designed to use those managed cloud services
instead of self-hosted equivalents (a database you install, a message broker you
run). The exact same Spring Boot code runs against **emulators** locally and
**real AWS** in production — you only change the endpoints (the `endpointOverride`
trick explained in Step 2).

### The mental model (read this first, it helps everything else)

A web app is usually built in **layers**, like floors of a building. Each floor
has one job and only talks to the floor below it:

```
Controller   ← receives HTTP requests, returns HTTP responses
    ↓
Service      ← contains the business rules ("the brain")
    ↓
Repository   ← knows how to talk to the database
    ↓
DynamoDB     ← actually stores the data
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

Spring Boot is a framework that **wires these layers together for you**. You
write small classes, annotate them, and Spring connects them automatically.

### Tools you need

- **JDK 21** — the Java compiler/runtime.
- **Gradle** — builds and packages the project (we use the Gradle wrapper, so you
  don't even need Gradle installed).
- **Docker** — runs the local emulators (DynamoDB Local, LocalStack, Redis).
- An editor (IntelliJ IDEA or VS Code).

### Project layout

```
src/main/java/com/example/orderplatform/
├── OrderPlatformApplication.java   ← the starting point
├── config/                          ← Spring configuration beans
├── controller/                      ← HTTP endpoints (orders + customers)
├── model/                           ← data classes (Customer, Order, OrderItem)
├── repository/                      ← DynamoDB access (Order + Customer)
├── service/                         ← business logic (Order + Customer)
├── event/                           ← event published to SNS
├── exception/                       ← not-found exceptions
└── messaging/                       ← SNS/SQS publishing and consuming
    ├── MessagingResources.java      ← topic/queue provisioning
    ├── publisher/                  ← publishes events to SNS
    ├── consumer/                    ← order processors (PROCESSED / NOTIFIED / SHIPPING)
    └── notification/               ← email/SMS notification pipeline
src/main/resources/
├── application.yml                  ← configuration
├── cloudformation/security.yaml     ← Step 4 IaC template
└── templates/                       ← notification templates (email body)
```

---

## The application at a glance

A quick reference for the key facts, before we go line by line.

### Technology stack

| Technology | What it is | Why we use it |
|------------|-----------|---------------|
| **Java 21** | Programming language | Modern, widely-used, runs the app |
| **Spring Boot 3.3** | Web framework | Handles HTTP, configuration, and "wiring" the layers together |
| **AWS SDK v2** | Library to talk to AWS | Lets Java code use DynamoDB, SNS, SQS, and SES |
| **DynamoDB** | NoSQL database (AWS) | Stores customers & orders — fast, scalable, serverless |
| **SNS** | Pub/sub messaging (AWS) | Fans out order events to multiple subscribers |
| **SQS** | Queue messaging (AWS) | Buffers messages for asynchronous processing |
| **SES** | Email service (AWS) | Sends the order-confirmation emails |
| **Redis / ElastiCache** | In-memory cache | Fast reads for `GET /orders/{id}` |
| **Gradle** | Build tool | Compiles and packages the app |
| **Swagger/OpenAPI** | API documentation | Interactive UI to explore and test the API |
| **Docker** | Container runtime | Runs local emulators (DynamoDB Local, LocalStack, Redis) |
| **Testcontainers / JUnit** | Testing | Unit & integration tests |

### Data model

There are three "objects" (Java classes) that describe our data.

**`Customer`** (who places orders):

| Field | Type | Meaning |
|-------|------|---------|
| `customerId` | String | Unique identifier (the **partition key** in DynamoDB) |
| `name` | String | Customer's name |
| `email` | String | Email address (used by the email notification) |
| `phoneNumber` | String | Phone number in E.164 (used by the SMS notification) |

**`Order`** (the main entity):

| Field | Type | Meaning |
|-------|------|---------|
| `orderId` | String | Unique identifier (the **partition key** in DynamoDB) |
| `customerId` | String | Who placed the order (must reference an existing `Customer`) |
| `status` | String | Lifecycle state (`CREATED` → `PROCESSED` → `NOTIFIED` / `SHIPPING`) |
| `items` | List of `OrderItem` | The products purchased |
| `totalAmount` | BigDecimal | Total cost (calculated, not typed by user) |
| `createdAt` | Instant | When the order was created |

**`OrderItem`** (a line in the order):

| Field | Type | Meaning |
|-------|------|---------|
| `productId` | String | Product identifier |
| `productName` | String | Human-readable name |
| `quantity` | Integer | How many |
| `unitPrice` | BigDecimal | Price of one unit |

**DynamoDB tables:** `Customers` (partition key `customerId`) and `Orders`
(partition key `orderId`).

> A *partition key* is DynamoDB's way of uniquely identifying and physically
> distributing a record — like the "primary key" in a traditional database.

> **Referential integrity:** an order can only be created if its `customerId`
> points to an existing customer. The `customerId` is **immutable** after
> creation — an order can't be reassigned to a different customer.

### The REST API

The API follows **REST** conventions: HTTP methods map to actions on resources.

| Method | Endpoint | What it does | Success code |
|--------|----------|--------------|--------------|
| `POST` | `/api/customers` | Create a customer | `201 Created` |
| `GET` | `/api/customers/{customerId}` | Fetch one customer | `200 OK` |
| `POST` | `/api/orders` | Create a new order | `201 Created` |
| `GET` | `/api/orders/{orderId}` | Fetch one order | `200 OK` |
| `GET` | `/api/orders` | List all orders | `200 OK` |
| `PUT` | `/api/orders/{orderId}` | Update an order | `200 OK` |
| `DELETE` | `/api/orders/{orderId}` | Delete an order | `204 No Content` |

If you request an order that doesn't exist, the API returns **`404 Not Found`**
with a helpful error message like `{"error": "Order not found: 123"}`.

Creating an order requires an existing customer. A missing or unknown
`customerId` returns **`404`** (`{"error": "Customer not found: ..."}`), and
trying to change an order's `customerId` returns **`400`**.

**Example** — first create a customer, then create an order:

```json
{
  "customerId": "cust-123",
  "name": "John",
  "email": "john@example.com",
  "phoneNumber": "+971501234567"
}
```

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

## Step 1 — The Spring Boot skeleton

The very first thing is a tiny class that "turns on" the application.

### File: `OrderPlatformApplication.java`

```java
package com.example.orderplatform;                          // (1)

import org.springframework.boot.SpringApplication;          // (2)
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication                                      // (3)
@EnableScheduling                                          // (4)
public class OrderPlatformApplication {

    public static void main(String[] args) {               // (5)
        SpringApplication.run(OrderPlatformApplication.class, args);  // (6)
    }
}
```

**Line-by-line:**

1. **`package ...`** — Every Java file belongs to a "package" (a folder path in
   dot form). This one is `com.example.orderplatform`. It groups related classes
   together and avoids name clashes.

2. **`import ...`** — Like "using" in other languages. It lets us use classes
   without typing their full name every time.

3. **`@SpringBootApplication`** — An **annotation** (a label starting with `@`).
   It tells Spring Boot: *"This is the main class — set up auto-configuration,
   find all my components, and start the app."* It's the on-switch for the whole
   framework.

4. **`@EnableScheduling`** — Turns on background-task support. We need this in
   Step 3 for the consumers that poll SQS every few seconds.

5. **`public static void main(...)`** — Every Java program starts here; it's the
   entry point the JVM calls.

6. **`SpringApplication.run(...)`** — Hands control to Spring Boot. It reads your
   config, creates all your beans, starts the web server, and keeps the app
   running until you stop it.

> **What is a "bean"?** A bean is an object that Spring creates and manages for
> you. When you write `@Service` or `@Component`, Spring instantiates it and
> gives it to other beans that need it. This is **dependency injection** — objects
> don't create their own dependencies; Spring hands them in via the constructor.

### File: `build.gradle` (the build config)

The build file lists our dependencies — the libraries we borrow. Think of it as
a shopping list:

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.3.5'        // (1)
    id 'io.spring.dependency-management' version '1.1.6'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)      // (2)
    }
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'      // (3)
    implementation "org.springdoc:springdoc-openapi-starter-webmvc-ui:..."  // (4)
    implementation "software.amazon.awssdk:dynamodb:..."                   // (5)
    implementation "software.amazon.awssdk:sns:..."                        // (6)
    implementation "software.amazon.awssdk:sqs:..."                        // (6)
    implementation "software.amazon.awssdk:ses:..."                        // (6)
    implementation 'org.springframework.boot:spring-boot-starter-data-redis' // (7)
    implementation 'org.springframework.boot:spring-boot-starter-cache'      // (7)
    testImplementation 'org.springframework.boot:spring-boot-starter-test'    // (8)
}
```

**Line-by-line:**

1. **`plugins`** — Adds build behavior. `org.springframework.boot` gives us
   `bootRun` and `bootJar` tasks. `io.spring.dependency-management` manages
   versions automatically.

2. **`languageVersion = 21`** — Build with Java 21.

3. **`spring-boot-starter-web`** — The "starter" that gives us HTTP: embedded
   Tomcat, JSON support, and the `@RestController`/`@GetMapping` annotations.
   "Starter" = a bundle of related libraries.

4. **`springdoc-openapi-starter-webmvc-ui`** — Swagger UI, the interactive web
   page where you click buttons to call the API.

5. **`software.amazon.awssdk:dynamodb`** — The AWS SDK for DynamoDB
   (`dynamodb-enhanced` is a friendlier layer on top).

6. **`sns` / `sqs` / `ses`** — AWS SDK clients for messaging and email (Step 3).

7. **`spring-boot-starter-data-redis` + `-cache`** — Redis client + caching
   annotations (Step 5).

8. **`testImplementation`** — Libraries used *only* when running tests, not
   bundled into the running app.

**Done with Step 1.** We have a project that builds and starts (it just has no
endpoints yet).

---

## Step 2 — REST API + DynamoDB

Now we add the actual order functionality — the biggest step. It creates the
four layers plus the DynamoDB wiring.

### 2a. The data model — `Order.java` and `OrderItem.java`

These classes describe **what an order looks like**: plain Java objects (POJOs)
with private fields and public getters/setters.

#### File: `OrderItem.java`

```java
@DynamoDbBean                                  // (1)
public class OrderItem {
    private String productId;                  // (2)
    private String productName;
    private Integer quantity;
    private BigDecimal unitPrice;

    public String getProductId() { return productId; }     // (3)
    public void setProductId(String productId) { this.productId = productId; }
}
```

1. **`@DynamoDbBean`** — Tells the AWS enhanced client "this object maps to a
   DynamoDB item."

2. **`private` fields** — Data is hidden (encapsulation). `BigDecimal` is used for
   money because `double`/`float` can produce rounding errors.

3. **Getters/setters** — The enhanced DynamoDB client reads attribute names from
   getter/setter names, so `getProductId()` becomes the attribute `productId`.

#### File: `Order.java`

```java
@DynamoDbBean
public class Order {
    private String orderId;
    private String customerId;
    private String status;
    private List<OrderItem> items;      // a list of OrderItem
    private BigDecimal totalAmount;
    private Instant createdAt;          // a timestamp

    @DynamoDbPartitionKey              // (1)
    public String getOrderId() { return orderId; }
}
```

1. **`@DynamoDbPartitionKey`** — The most important annotation. It marks `orderId`
   as the **primary key**. DynamoDB uses it to uniquely identify and distribute
   each record (like a SQL primary key).

> `Instant` is a `java.time` type for a moment in time. `List<OrderItem>` means
> one order can contain many line items.

### 2b. The repository — `OrderRepository.java`

The repository is the **only** class that talks directly to DynamoDB.

```java
@Repository                                        // (1)
public class OrderRepository {

    private final DynamoDbTable<Order> orderTable; // (2)

    public OrderRepository(DynamoDbTable<Order> orderTable) {   // (3)
        this.orderTable = orderTable;
    }

    public void save(Order order) {
        orderTable.putItem(order);                  // (4)
    }

    public Optional<Order> findById(String orderId) {
        Order order = orderTable.getItem(r -> r.key(k -> k.partitionValue(orderId))); // (5)
        return Optional.ofNullable(order);          // (6)
    }

    public List<Order> findAll() {
        return orderTable.scan().items().stream().collect(Collectors.toList()); // (7)
    }

    public void delete(String orderId) {
        orderTable.deleteItem(r -> r.key(k -> k.partitionValue(orderId))); // (8)
    }
}
```

1. **`@Repository`** — A marker like `@Service`/`@Component` that tells Spring
   "create this as a bean."

2. **`DynamoDbTable<Order>`** — The enhanced client's type-safe handle to the
   `Orders` table. `<Order>` means "this table holds `Order` objects."

3. **Constructor injection** — Dependency injection in action. The constructor
   declares "I need a `DynamoDbTable<Order>`"; Spring passes it in. We store it in
   a `final` field (set once, never changed).

4. **`putItem(order)`** — Writes (or overwrites) the order.

5. **`getItem(...)`** — Reads one item by key. `k.partitionValue(orderId)` means
   "where partition key = this orderId."

6. **`Optional.ofNullable(...)`** — DynamoDB returns `null` if not found. We wrap
   it in `Optional`, Java's "maybe a value, maybe not" box, to prevent
   `NullPointerException` and force callers to handle "not found."

7. **`scan()`** — Reads **all** items. (Fine for a demo; a huge table would use
   `Query`, an optimization.)

8. **`deleteItem(...)`** — Removes the item by key.

### 2c. The exception — `OrderNotFoundException.java`

```java
public class OrderNotFoundException extends RuntimeException {      // (1)
    public OrderNotFoundException(String orderId) {
        super("Order not found: " + orderId);                        // (2)
    }
}
```

1. Extends `RuntimeException` — an "unchecked" exception; methods throwing it
   don't need to declare it (the idiomatic Spring way for "not found").

2. Calls the parent constructor with a human-readable message. We map this to a
   404 later.

### 2d. The service — `OrderService.java`

The service holds the **business rules** — where the "thinking" happens.

```java
@Service                                                        // (1)
public class OrderService {

    public static final String ORDERS_CACHE = "orders";

    private final OrderRepository orderRepository;              // (2)
    private final OrderEventPublisher orderEventPublisher;

    public OrderService(OrderRepository orderRepository,
                        OrderEventPublisher orderEventPublisher) {
        this.orderRepository = orderRepository;
        this.orderEventPublisher = orderEventPublisher;
    }

    public Order create(Order order) {                          // (3)
        // Referential integrity: the order must reference an existing customer.
        if (order.getCustomerId() == null || order.getCustomerId().isBlank()) {
            throw new CustomerNotFoundException("(missing)");   // (3a)
        }
        customerService.findById(order.getCustomerId());        // (3b)
        if (order.getOrderId() == null || order.getOrderId().isBlank()) {
            order.setOrderId(UUID.randomUUID().toString());     // (4)
        }
        if (order.getCreatedAt() == null) {
            order.setCreatedAt(Instant.now());                  // (5)
        }
        if (order.getStatus() == null || order.getStatus().isBlank()) {
            order.setStatus("CREATED");                         // (6)
        }
        order.setTotalAmount(computeTotal(order));              // (7)
        orderRepository.save(order);                            // (8)

        orderEventPublisher.publish(new OrderCreatedEvent(...)); // (9) — Step 3
        return order;
    }

    @Cacheable(cacheNames = ORDERS_CACHE, key = "#orderId")     // (10) — Step 5
    public Order findById(String orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId)); // (11)
    }

    public Order update(String orderId, Order updated) { ... }   // (12)
    public Order updateStatus(String orderId, String status) { ... } // (13)
    @CacheEvict(cacheNames = ORDERS_CACHE, key = "#orderId")
    public void delete(String orderId) { ... }                  // (14)

    private BigDecimal computeTotal(Order order) { ... }        // (15)
}
```

1. **`@Service`** — Create this as a bean (business layer).

2. **Dependencies** — the repository (save/load), the customer service (referential
   integrity), and (Step 3) the publisher.

3. **`create`** — the "create order" flow.

3a–3b. **Referential integrity** — before anything else, reject a blank/missing
`customerId`, then verify the customer actually exists. This guarantees an order
is always issued by a real customer.

4. **`UUID.randomUUID()`** — If no `orderId` supplied, generate a random
   globally-unique ID.

5. **`Instant.now()`** — Stamp current time if not provided.

6. **default `"CREATED"`** — new orders start in the `CREATED` state.

7. **`computeTotal`** — calculate the total automatically (see 15).

8. **`save`** — persist to DynamoDB.

9. **`publish`** — (Step 3) announce the creation to SNS.

10. **`@Cacheable`** — (Step 5) "check the cache first; if present, return it and
    skip the method body."

11. **`.orElseThrow(...)`** — if `Optional` is empty, throw the 404 exception
    instead of returning null.

12. **`update`** — load existing, copy over only provided (non-null) fields,
    recalculate total if items changed, save.

13. **`updateStatus`** — change only status and save (used by async consumers).

14. **`@CacheEvict`** — (Step 5) after deleting, remove the cached copy.

15. **`computeTotal`** — the business math: sum of `unitPrice × quantity` over all
    items; zero if no items.

### 2e. The controller — `OrderController.java`

The controller is the **front door**: turns HTTP requests into service calls and
results back into HTTP responses.

```java
@RestController                                             // (1)
@RequestMapping("/api/orders")                              // (2)
public class OrderController {

    private final OrderService orderService;                // (4)

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping                                             // (5)
    public ResponseEntity<Order> create(@RequestBody Order order) {  // (6)
        return ResponseEntity.status(HttpStatus.CREATED)      // (7)
                .body(orderService.create(order));
    }

    @GetMapping("/{orderId}")                                // (8)
    public ResponseEntity<Order> get(@PathVariable String orderId) {  // (9)
        return ResponseEntity.ok(orderService.findById(orderId));
    }

    @GetMapping                                              // (10)
    public ResponseEntity<List<Order>> list() {
        return ResponseEntity.ok(orderService.findAll());
    }

    @PutMapping("/{orderId}")                               // (11)
    public ResponseEntity<Order> update(@PathVariable String orderId,
                                        @RequestBody Order order) {
        return ResponseEntity.ok(orderService.update(orderId, order));
    }

    @DeleteMapping("/{orderId}")                            // (12)
    public ResponseEntity<Void> delete(@PathVariable String orderId) {
        orderService.delete(orderId);
        return ResponseEntity.noContent().build();           // (13)
    }

    @ExceptionHandler(OrderNotFoundException.class)          // (14)
    public ResponseEntity<Map<String, String>> handleNotFound(OrderNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", e.getMessage()));      // (15)
    }
}
```

1. **`@RestController`** — "This class handles HTTP and returns JSON"
   (`@Controller` + `@ResponseBody` combined).

2. **`@RequestMapping("/api/orders")`** — every endpoint starts with `/api/orders`.

4. **Inject the service** — same pattern as before.

5. **`@PostMapping`** — handle `POST /api/orders` (create).

6. **`@RequestBody Order order`** — Spring reads the JSON body and converts it to
   an `Order` automatically (via Jackson).

7. **`status(CREATED)`** — return HTTP **201 Created** with the created order as
   the body (201 = "created", 200 = "fetched").

8. **`@GetMapping("/{orderId}")`** — handle `GET /api/orders/some-id`. `{orderId}`
   is a path placeholder.

9. **`@PathVariable String orderId`** — pull `{orderId}` from the URL into the
   parameter.

10. **`@GetMapping`** (no path) — handle `GET /api/orders` (list all).

11. **`@PutMapping("/{orderId}")`** — handle `PUT` (update).

12. **`@DeleteMapping("/{orderId}")`** — handle `DELETE`.

13. **`noContent()`** — HTTP **204 No Content** (deleted, no body).

14. **`@ExceptionHandler(OrderNotFoundException.class)`** — whenever that exception
    is thrown, run this method.

15. **`Map.of("error", ...)`** — HTTP **404** with body
    `{"error": "Order not found: 123"}`. This turns the exception into a proper
    404 response.

### 2f. The DynamoDB configuration — `DynamoDbConfig.java`

This builds the `DynamoDbTable<Order>` bean the repository needs.

```java
@Configuration                                              // (1)
public class DynamoDbConfig {

    @Value("${aws.region:us-east-1}")                       // (2)
    private String region;

    @Value("${aws.dynamodb.endpoint:}")                     // (3)
    private String endpoint;

    @Value("${aws.dynamodb.tableName:Orders}")              // (4)
    private String tableName;

    @Value("${aws.accessKeyId:}")                           // (5)
    private String accessKeyId;

    @Bean                                                     // (6)
    public DynamoDbClient dynamoDbClient() {
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(region));                  // (7)
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));  // (8)
        }
        if (accessKeyId != null && !accessKeyId.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey))); // (9)
        }
        return builder.build();
    }

    @Bean                                                     // (10)
    public DynamoDbTable<Order> orderTable(DynamoDbEnhancedClient enhancedClient) {
        DynamoDbTable<Order> table = enhancedClient.table(tableName,
                TableSchema.fromBean(Order.class));           // (11)
        ensureTableExists(table);                             // (12)
        return table;
    }
}
```

1. **`@Configuration`** — "This class defines beans."

2. **`@Value("${aws.region:us-east-1}")`** — read `aws.region` from
   `application.yml`; `:us-east-1` is the default if missing.

3. **`${aws.dynamodb.endpoint:}`** — the DynamoDB URL. Empty by default ("real
   AWS"); overridden to `http://localhost:8000` locally.

4. **`${aws.dynamodb.tableName:Orders}`** — table name, default `Orders`.

5. **Credentials** — empty for real AWS (default chain); `local`/`local` locally.

6. **`@Bean`** — expose the return value as a bean.

7. **`.region(...)`** — which AWS region.

8. **`endpointOverride(...)`** — *the key trick for local dev.* Redirects all
   calls to a local URL (DynamoDB Local at `http://localhost:8000`) instead of
   real AWS.

9. **`StaticCredentialsProvider`** — fake credentials for the local emulator.

10. **`orderTable` bean** — the enhanced client wraps the low-level client into a
    type-safe `DynamoDbTable<Order>`.

11. **`TableSchema.fromBean(Order.class)`** — auto-map the `Order` class (and its
    `@DynamoDbPartitionKey`) to DynamoDB's schema.

12. **`ensureTableExists`** — describe the table; if missing (caught
    `ResourceNotFoundException`), create it. Convenient for local dev.

### 2g. Swagger — `OpenApiConfig.java`

```java
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI orderPlatformOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Order Platform API")
                .version("v1.0.0"));
    }
}
```

Sets the title/version shown in the Swagger UI at
`http://localhost:8080/swagger-ui/index.html`. Documentation only.

### 2h. Configuration — `application.yml`

```yaml
server:
  port: 8080                     # the app listens on port 8080

spring:
  application:
    name: order-platform

aws:
  region: us-east-1
  dynamodb:
    endpoint: http://localhost:8000   # → DynamoDB Local
    tableName: Orders
  accessKeyId: local                 # fake creds for the emulator
  secretAccessKey: local
```

`application.yml` is Spring Boot's config file. The `@Value(...)` annotations read
from here. `endpoint: http://localhost:8000` redirects the app to the local
emulator.

**Done with Step 2.** We now have a working CRUD API backed by DynamoDB. You can
`docker compose up -d` (starts DynamoDB Local), `gradlew bootRun`, and hit the
endpoints.

---

## Step 3 — Asynchronous processing (SNS + SQS + Lambda)

Now, when an order is created, we **announce** it and let other parts react in the
background. The flow:

```
                        ┌── SQS ──► Order Processor   → marks order "PROCESSED"
                        │
                        ├── SQS ──► Lambda-like       → marks order "NOTIFIED"
                        │
Order API ──► SNS ─────┼── SQS ──► Email handler    → confirmation email (SES)
   (create)             │
                        ├── SQS ──► SMS handler       → text message (SNS)
                        │
                        └── SQS ──► Shipping processor → marks order "SHIPPING"
```

**The ideas, in one line each:**

- **SNS** = a megaphone. Publish once, many subscribers get a copy ("fan-out").
- **SQS** = a queue. Messages wait in line for a worker.
- **Lambda** = a serverless function (emulated locally with a second queue).
- **email** = a consumer looks up the customer and sends email through **SES**.
- **sms** = a consumer looks up the customer and sends a text through **SNS**
  (direct publish to the customer's phone number).
- **shipping** = a consumer marks the order `SHIPPING` (a workflow, like `PROCESSED`).

> The **email** and **SMS** branches are *notification* pipelines (they build and
> send a message to the customer), while the **processor**, **lambda**, and
> **shipping** branches are *workflow* consumers (they change the order's status).
> Notification code lives in `messaging/notification/`; workflow consumers live in
> `messaging/consumer/`.

### 3a. The event — `OrderCreatedEvent.java`

```java
public record OrderCreatedEvent(                       // (1)
        String orderId,
        String customerId,
        String status,
        BigDecimal totalAmount,
        Instant createdAt) {
}
```

1. **`record`** — A Java 16+ shorthand for an **immutable data class**. One line
   replaces a whole class with fields, constructor, getters, `equals`,
   `hashCode`, and `toString`. "Immutable" means its values can't change after
   creation — perfect for an event (a record of something that already happened).

Jackson (Spring's JSON library) turns this record into JSON when we publish it.

### 3b. The clients — `MessagingConfig.java`

Mirrors `DynamoDbConfig`, but creates `SnsClient`, `SqsClient`, and `SesClient`
beans:

```java
@Configuration
public class MessagingConfig {
    @Value("${aws.sns.endpoint:}")  private String snsEndpoint;   // (1)
    @Value("${aws.sqs.endpoint:}")  private String sqsEndpoint;
    @Value("${aws.ses.endpoint:}")  private String sesEndpoint;

    @Bean
    public SnsClient snsClient() {
        SnsClient.Builder builder = SnsClient.builder().region(Region.of(region));
        if (snsEndpoint != null && !snsEndpoint.isBlank()) {
            builder.endpointOverride(URI.create(snsEndpoint));     // (2)
        }
        // ... credentials ...
        return builder.build();
    }
    // sqsClient() and sesClient() are identical but return SqsClient / SesClient
}
```

1. Reads the SNS/SQS/SES endpoints from config (all `http://localhost:4566`
   locally — LocalStack's single port for all services).

2. Same `endpointOverride` trick — redirect to LocalStack instead of real AWS.

> **`SesClient`** is the AWS client for **SES** (Simple Email Service). It's the
> "email provider" that the email notification pipeline uses to actually send mail.

### 3c. Provisioning resources — `MessagingResources.java`

This creates the topic, the **five** queues, and their subscriptions on startup
(so you don't have to create them by hand).

```java
@Component                                                  // (1)
public class MessagingResources {

    @PostConstruct                                           // (2)
    void init() {
        try {
            topicArn = ensureTopic();                        // (3)
            processorQueueUrl = ensureQueue(processorQueueName);
            lambdaQueueUrl    = ensureQueue(lambdaQueueName);
            emailQueueUrl     = ensureQueue(emailQueueName);  // (4)
            smsQueueUrl       = ensureQueue(smsQueueName);
            shippingQueueUrl  = ensureQueue(shippingQueueName);
            subscribeQueue(topicArn, processorQueueUrl);     // (5)
            subscribeQueue(topicArn, lambdaQueueUrl);
            subscribeQueue(topicArn, emailQueueUrl);
            subscribeQueue(topicArn, smsQueueUrl);
            subscribeQueue(topicArn, shippingQueueUrl);
        } catch (Exception e) {
            log.warn("Could not provision messaging resources ...", e);
        }
    }

    private void subscribeQueue(String topicArn, String queueUrl) {
        String queueArn = sqsClient.getQueueAttributes(...); // (6)
        subscribe(topicArn, "sqs", queueArn);
    }

    private void subscribe(String topicArn, String protocol, String endpoint) {
        snsClient.subscribe(SubscribeRequest.builder()
                .topicArn(topicArn).protocol(protocol).endpoint(endpoint).build()); // (7)
    }
}
```

1. **`@Component`** — Generic bean (not controller/service/repository).

2. **`@PostConstruct`** — Run this method automatically after the bean is created
   (i.e., at startup).

3. **`ensureTopic`** — `createTopic` is *idempotent*: if the topic already exists,
   AWS/LocalStack returns the existing one. So "ensure" = "create if missing."

4. **Five queues** — one per downstream branch: processor, lambda, email, sms, and
   shipping.

5. **`subscribeQueue`** — Wire each SQS queue to the topic.

6. SNS subscribes SQS by the *queue's ARN*, so we first look up the queue's ARN.

7. **`subscribe(protocol, endpoint)`** — the generic helper. For `"sqs"` the
   endpoint is the queue's **ARN**.

> **Why no `"email"` / `"sms"` SNS protocols anymore?** Earlier versions subscribed
> SNS directly to email/SMS endpoints. That required confirmation and couldn't send
> to an arbitrary address/phone. Now email and SMS are **SQS queues consumed by our
> own handlers**, which call **SES** (email) and **SNS direct publish** (SMS) with
> the customer's stored `email` / `phoneNumber`. LocalStack records these calls but
> does not actually deliver email/SMS — that only happens against real AWS.

### 3d. Publishing — `OrderEventPublisher.java`

```java
@Component
public class OrderEventPublisher {

    public void publish(OrderCreatedEvent event) {
        String topicArn = messagingResources.topicArn();       // (1)
        if (topicArn == null || topicArn.isBlank()) {
            log.warn("SNS topic not available; skipping ..."); // (2)
            return;
        }
        try {
            String body = objectMapper.writeValueAsString(event); // (3)
            snsClient.publish(PublishRequest.builder()
                    .topicArn(topicArn).message(body).build());   // (4)
        } catch (Exception e) {
            log.error("Failed to publish ...", e);             // (5)
        }
    }
}
```

1. Get the (already-provisioned) topic ARN.

2. If messaging isn't available, **skip quietly** — order creation must not fail
   just because messaging is down.

3. **`writeValueAsString`** — Serialize the `OrderCreatedEvent` record to a JSON
   string (e.g. `{"orderId":"...","customerId":"..."}`).

4. **`publish`** — Send the JSON to the SNS topic.

5. **catch + log** — "Best-effort" publishing. Never let a messaging failure break
   the order creation flow.

### 3e. The consumers — `OrderProcessor.java` and `OrderLambdaHandler.java`

These poll SQS every few seconds and react to messages.

```java
@Component
public class OrderProcessor {

    @Scheduled(fixedDelayString = "${aws.sqs.pollIntervalMs:5000}")  // (1)
    public void poll() {
        String queueUrl = messagingResources.processorQueueUrl();    // (2)
        if (queueUrl == null) return;                                // (3)
        List<Message> messages = sqsClient.receiveMessage(
                ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)                     // (4)
                        .waitTimeSeconds(5)                           // (5)
                        .build()).messages();
        for (Message message : messages) {
            process(message, queueUrl);                              // (6)
        }
    }

    private void process(Message message, String queueUrl) {
        try {
            OrderCreatedEvent event = parseEvent(message.body());    // (7)
            orderService.updateStatus(event.orderId(), "PROCESSED"); // (8)
        } finally {
            sqsClient.deleteMessage(...);                            // (9)
        }
    }
}
```

1. **`@Scheduled(fixedDelayString = "...")`** — Run repeatedly, waiting 5000ms
   between the end of one run and the start of the next. This is why we added
   `@EnableScheduling` in Step 1. (Polling is simpler than a long-running
   listener and works the same against LocalStack.)

2. Get the queue URL (null if messaging isn't provisioned).

3. If no queue, do nothing this cycle.

4. **`maxNumberOfMessages(10)`** — Grab up to 10 messages at a time.

5. **`waitTimeSeconds(5)`** — Long polling: if empty, wait up to 5s (more
   efficient than hammering).

6. Process each message.

7. **`parseEvent`** — SNS wraps the payload in an envelope: the actual JSON event
   is inside a `"Message"` field. This unwraps it back into an
   `OrderCreatedEvent`.

8. **`orderService.updateStatus(event.orderId(), "PROCESSED")`** — Mark the order
   as processed. Crucially, this goes through the **service** (not the repository
   directly), so the cache (Step 5) is also updated.

9. **`deleteMessage`** — In SQS you must explicitly delete a message after
   processing, otherwise it reappears (this is how SQS guarantees "at-least-once"
   delivery). The `finally` ensures we always delete, even if processing threw.

`OrderLambdaHandler` is structurally identical, but:
- reads from `lambdaQueueUrl()` instead of `processorQueueUrl()`,
- calls `updateStatus(..., "NOTIFIED")` instead of `"PROCESSED"`.

It stands in for a real Lambda. The comments in that file explain how to swap it
for a real Lambda in AWS.

`ShippingProcessor` is also structurally identical — it reads from
`shippingQueueUrl()` and calls `updateStatus(..., "SHIPPING")` — driving the
shipping workflow.

### 3f. The notification consumers — email and SMS

The email and SMS branches are **notification pipelines** (not just status
updates). Each consumes its own queue, looks up the customer, builds a
`NotificationMessage`, and runs it through explicit stages before sending:

```
Email:  SQS → Validate → Load template → Build email → Email provider (SES) → Success
SMS:    SQS → Validate → Build SMS → SMS provider (SNS) → Success
```

Both unwrap the SNS envelope and delete the SQS message in a `finally` block,
exactly like `OrderProcessor`.

**Email** — `EmailNotificationHandler`:

```java
void handle(OrderCreatedEvent event, Customer customer) {
    NotificationMessage notification = new NotificationMessage(
            "notif-" + UUID.randomUUID(),
            "ORDER_CREATED", "EMAIL", "ORDER_CREATED",
            dataFor(event, customer),               // orderId, customerName, totalAmount, currency
            customer.getEmail(),                    // recipient
            "Order " + event.orderId() + " confirmed", // subject
            null, null, Instant.now());

    validator.validate(notification);              // 1. validate
    String template = templateService.load("ORDER_CREATED"); // 2. load template
    String body = templateService.render(template, notification.data()); // 3. build email
    emailProvider.send(notification.recipient(), notification.subject(), body); // 4. SES
    log.info("... success ...");                   // 5. success
}
```

The email provider is **SES**: `EmailProvider` calls
`sesClient.sendEmail(SendEmailRequest...)` with the `aws.ses.senderEmail` "from"
address. The `TemplateService` loads a body template from
`src/main/resources/templates/ORDER_CREATED.txt` and substitutes `{{placeholders}}`
with the message data.

**SMS** — `SmsNotificationHandler`:

```java
void handle(OrderCreatedEvent event, Customer customer) {
    String text = "Your order " + event.orderId() + " has been confirmed.";
    NotificationMessage notification = new NotificationMessage(
            "notif-" + UUID.randomUUID(),
            "ORDER_CREATED", "SMS", "ORDER_CREATED",
            dataFor(event, customer),               // orderId, customerName
            null, null,
            customer.getPhoneNumber(),              // phoneNumber
            text, Instant.now());

    validator.validate(notification);              // 1. validate
    smsProvider.send(notification.phoneNumber(), notification.message()); // 2. SNS direct publish
    log.info("... success ...");                   // 3. success
}
```

The SMS provider is **SNS direct publish**: `SmsProvider` calls
`snsClient.publish(PublishRequest.phoneNumber(...))`. This is different from the
email path — SNS can send a text message to any phone number without a
subscription, whereas email needs SES (SNS can't send to an arbitrary address
without subscription + confirmation).

### 3g. Wiring it into the service

Back in `OrderService.create`, after saving:

```java
orderEventPublisher.publish(new OrderCreatedEvent(
        order.getOrderId(), order.getCustomerId(),
        order.getStatus(), order.getTotalAmount(), order.getCreatedAt()));
```

This publishes the event, which SNS fans out to **five** queues. The workflow
consumers turn the order into `PROCESSED` / `NOTIFIED` / `SHIPPING`, and the
notification consumers email/SMS the customer.

### 3h. Configuration and Docker

`application.yml` gains:

```yaml
aws:
  sns:
    endpoint: http://localhost:4566
    topicName: order-events
  sqs:
    endpoint: http://localhost:4566
    processorQueueName: order-processor-queue
    lambdaQueueName: order-lambda-queue
    emailQueueName: order-email-queue
    smsQueueName: order-sms-queue
    shippingQueueName: order-shipping-queue
    pollIntervalMs: 5000
  ses:
    endpoint: http://localhost:4566
    senderEmail: no-reply@example.com
  currency: AED
```

`docker-compose.yml` gains a **LocalStack** service (emulates SNS+SQS+SES on port
4566):

```yaml
localstack:
  image: localstack/localstack:latest
  environment:
    - SERVICES=sns,sqs,ses
  ports:
    - "4566:4566"
```

**Done with Step 3.** Order creation now fans out asynchronously to five
consumers.

---

## Step 4 — Security (IAM + Secrets Manager + KMS)

Step 4 is **infrastructure-as-code** (IaC): a CloudFormation template that defines
the security layer. There is **no Java code** in this step — it's a YAML file that
AWS reads to create resources.

```
IAM
 ├── Spring Boot permissions
 ├── Lambda permissions
 └── EKS permissions

Secrets Manager
 └── application secrets

KMS
 └── encryption keys
```

**The ideas, in one line each:**

- **IAM** = AWS's permission system ("who can do what to which resource").
- **Secrets Manager** = a safe place to store passwords/keys (not in code).
- **KMS** = manages encryption keys (scrambles data so only authorized parties
  can read it).

### File: `src/main/resources/cloudformation/security.yaml`

The template is a YAML document with `Parameters`, `Resources`, and `Outputs`.

```yaml
AWSTemplateFormatVersion: "2010-09-09"
Description: >                          # (1)
  Order Platform - Security stack (Step 4).

Parameters:                             # (2)
  ApplicationName:
    Type: String
    Default: order-platform
  Stage:
    Type: String
    Default: dev
    AllowedValues: [dev, staging, prod]
```

1. **`Description`** — human-readable summary (the `>` folds lines into one
   string).

2. **`Parameters`** — values you can override when deploying. `ApplicationName`
   prefixes resource names; `Stage` lets you deploy the same stack for `dev`,
   `staging`, and `prod`.

#### The KMS key

```yaml
Resources:
  OrdersEncryptionKey:                   # (3)
    Type: AWS::KMS::Key
    Properties:
      EnableKeyRotation: true            # (4)
      KeyPolicy:                         # (5)
        Version: "2012-10-17"
        Statement:
          - Sid: Enable IAM and root permissions
            Effect: Allow
            Principal:
              AWS: !Sub "arn:${AWS::Partition}:iam::${AWS::AccountId}:root"
            Action: kms:*
            Resource: "*"

  OrdersEncryptionKeyAlias:              # (6)
    Type: AWS::KMS::Alias
    Properties:
      AliasName: !Sub "alias/${ApplicationName}-${Stage}"
      TargetKeyId: !Ref OrdersEncryptionKey
```

3. **`OrdersEncryptionKey`** — the logical name. The type `AWS::KMS::Key` tells
   CloudFormation "create a KMS key."

4. **`EnableKeyRotation: true`** — AWS automatically rotates the key yearly.

5. **`KeyPolicy`** — the key's own permission document. This grants the AWS
   account root full control (`kms:*`); the IAM roles below then get narrower
   `kms:Decrypt`/`Encrypt` permissions.

6. **Alias** — a friendly name (`alias/order-platform-dev`) so you don't have to
   remember the key's random ID.

> **`!Sub`** and **`!Ref`** are CloudFormation *intrinsic functions*. `!Sub`
> substitutes variables into a string; `!Ref` returns another resource's value
> (here, the key's ID).

#### The Secrets Manager secret

```yaml
  ApplicationSecret:
    Type: AWS::SecretsManager::Secret
    Properties:
      Name: !Sub "${ApplicationName}/${Stage}/application"
      KmsKeyId: !Ref OrdersEncryptionKey       # (7)
      GenerateSecretString:                    # (8)
        SecretStringTemplate: !Sub '{"username":"${SecretsUsername}"}'
        GenerateStringKey: password
        PasswordLength: 32
        ExcludeCharacters: '"@/\'
```

7. **`KmsKeyId`** — encrypt this secret with **our** KMS key (not AWS's default).

8. **`GenerateSecretString`** — Secrets Manager *auto-generates* a strong random
   password and stores it as `{"username":"app-user","password":"<random>"}`.
   `ExcludeCharacters` avoids characters that break JSON.

#### The IAM roles

There are three roles. Here's the Spring Boot one (the most important):

```yaml
  SpringBootAppRole:
    Type: AWS::IAM::Role
    Properties:
      RoleName: !Sub "${ApplicationName}-app-${Stage}"
      AssumeRolePolicyDocument:                 # (9)
        Version: "2012-10-17"
        Statement:
          - Effect: Allow
            Principal:
              Service: ecs-tasks.amazonaws.com
            Action: sts:AssumeRole
      Policies:                                  # (10)
        - PolicyName: dynamodb-orders-access
          PolicyDocument:
            Version: "2012-10-17"
            Statement:
              - Effect: Allow
                Action:
                  - dynamodb:GetItem
                  - dynamodb:PutItem
                  - dynamodb:UpdateItem
                  - dynamodb:DeleteItem
                  - dynamodb:Scan
                  - dynamodb:Query
                Resource: !Sub "arn:${AWS::Partition}:dynamodb:${AWS::Region}:${AWS::AccountId}:table/Orders"
```

9. **`AssumeRolePolicyDocument`** — *who can assume this role*. Here, ECS tasks
   (i.e., the running app) can. In EKS you'd add a `Federated` (web identity)
   trust — the template has a commented-out example.

10. **`Policies`** — the actual permissions, following **least privilege**: the app
    can only touch the `Orders` table (the `Resource` restricts it to exactly that
    ARN), only publish to one SNS topic, only read one secret, and only decrypt
    with one key. This limits the "blast radius" if the app is compromised.

The other two roles follow the same shape:
- **`LambdaConsumerRole`** — `dynamodb:GetItem`/`UpdateItem` on `Orders` + KMS
  decrypt + `AWSLambdaBasicExecutionRole` (CloudWatch logs).
- **`EksClusterRole`** / **`EksNodeRole`** — EKS cluster + worker-node managed
  policies.

#### Outputs

```yaml
Outputs:
  KmsKeyArn:
    Value: !GetAtt OrdersEncryptionKey.Arn
  SecretArn:
    Value: !Ref ApplicationSecret
  SpringBootAppRoleArn:
    Value: !GetAtt SpringBootAppRole.Arn
```

**`Outputs`** expose the created resource ARNs so you can look them up after
deploying (to wire them into the app, Lambda, or EKS).

#### Deploying

```bash
aws cloudformation create-stack \
  --stack-name order-platform-security \
  --template-body file://src/main/resources/cloudformation/security.yaml \
  --parameters ParameterKey=Stage,ParameterValue=dev \
  --capabilities CAPABILITY_NAMED_IAM
```

`CAPABILITY_NAMED_IAM` is required because the stack creates IAM roles with
explicit names.

**Done with Step 4.** Security is defined as code, deployable to AWS with one
command.

---

## Step 5 — Redis caching

Finally, we cache reads so they're fast. The pattern is **cache-aside**:

```
GET /orders/{id}
        │
        ▼
      Redis
        │
   cache hit? ── yes ──► return cached order
        │
        no
        ▼
    DynamoDB   (load order)
        │
        ▼
      Redis   (store order)
        │
        ▼
      return
```

**The idea in one line:** check Redis first; on a miss, read DynamoDB and store
the result in Redis for next time.

### File: `RedisCacheConfig.java`

```java
@Configuration
@EnableCaching                                             // (1)
public class RedisCacheConfig {

    @Value("${spring.cache.redis.time-to-live:PT5M}")       // (2)
    private Duration timeToLive;

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper mapper = new ObjectMapper();           // (3)
        mapper.registerModule(new JavaTimeModule());        // (4)
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTyping(...);                  // (5)

        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(mapper); // (6)

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(SerializationPair.fromSerializer(serializer))
                .entryTtl(timeToLive);                       // (7)

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config).build();              // (8)
    }
}
```

1. **`@EnableCaching`** — Turn on Spring's caching abstraction (the `@Cacheable`
   etc. annotations).

2. **`@Value("${spring.cache.redis.time-to-live:PT5M}")`** — read the TTL
   (default 5 minutes) from config.

3. **`ObjectMapper`** — Jackson, the JSON (de)serializer.

4. **`registerModule(new JavaTimeModule())`** — teach Jackson about `java.time`
   types (the order's `createdAt` is an `Instant`). Without this, serializing an
   `Instant` would fail.

5. **`activateDefaultTyping`** — embeds the Java type in the JSON so Redis values
   deserialize back into the *correct* concrete class (`Order`, `OrderItem`) rather
   than a generic map.

6. **`GenericJackson2JsonRedisSerializer`** — the Redis value serializer that uses
   that mapper.

7. **`entryTtl(timeToLive)`** — every cached entry expires after 5 minutes, so
   stale data can never live forever.

8. **`RedisCacheManager.builder(...)`** — assemble the cache manager from the
   connection factory + config.

### The annotations on `OrderService`

We saw these in Step 2; now they mean something:

```java
@Cacheable(cacheNames = "orders", key = "#orderId")   // (9)
public Order findById(String orderId) { ... }

@CachePut(cacheNames = "orders", key = "#orderId")    // (10)
public Order updateStatus(String orderId, String status) { ... }

@CacheEvict(cacheNames = "orders", key = "#orderId")  // (11)
public void delete(String orderId) { ... }
```

9. **`@Cacheable`** — *the whole cache-aside pattern in one annotation.* Before
   running `findById`, Spring checks Redis for key `orders::<orderId>`. Hit →
   returns it (method body skipped). Miss → runs the method (reads DynamoDB),
   **stores the result in Redis**, then returns it.

10. **`@CachePut`** — *always* runs the method, but also writes the result to the
    cache. Used by `updateStatus` so when an async consumer marks an order
    `PROCESSED`/`NOTIFIED`/`SHIPPING`, the cache is refreshed (no stale `CREATED`).

11. **`@CacheEvict`** — after `delete`, remove the cached copy too.

### Configuration and Docker

`application.yml`:

```yaml
spring:
  cache:
    type: redis
    redis:
      time-to-live: PT5M
  data:
    redis:
      host: localhost
      port: 6379
```

`docker-compose.yml` adds Redis:

```yaml
redis:
  image: redis:7-alpine
  ports:
    - "6379:6379"
```

**Done with Step 5.** `GET /orders/{id}` is now served from Redis when possible.

> In AWS, swap local Redis for **ElastiCache for Redis**: point
> `spring.data.redis.host` at the cluster endpoint — no code changes needed.

---

## Testing strategy

| Test type | What it verifies | Needs Docker? |
|-----------|------------------|---------------|
| **Unit tests** (`OrderServiceTest`, `OrderControllerTest`, `OrderEventPublisherTest`, `OrderProcessorTest`, `ShippingProcessorTest`) | Individual classes in isolation (using Mockito to fake dependencies) | No |
| **Integration tests** (`OrderRepositoryIntegrationTest`, `OrderApiIntegrationTest`) | Real components together against a real DynamoDB (via Testcontainers) | Yes |

Run tests:

```bash
gradlew.bat test                          # all tests
gradlew.bat test --tests "*Test"          # unit tests only
gradlew.bat test --tests "*IntegrationTest"  # integration tests (needs Docker)
```

---

## How to run it

```bash
# 1. Start the local AWS emulators + Redis
docker compose up -d

# 2. Build & run the app (Windows: gradlew.bat, Linux/macOS: ./gradlew)
gradlew.bat bootRun
```

Then open the **Swagger UI** to interact with the API in your browser:

- Swagger UI: http://localhost:8080/swagger-ui/index.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs

### Local infrastructure (docker-compose.yml)

Running the full app locally requires two emulated AWS services plus Redis, all
started with Docker:

| Service | Image | Purpose | Port |
|---------|-------|---------|------|
| `dynamodb-local` | `amazon/dynamodb-local` | Emulates DynamoDB | 8000 |
| `localstack` | `localstack/localstack` | Emulates SNS + SQS + SES | 4566 |
| `redis` | `redis:7-alpine` | Cache | 6379 |

---

## Glossary

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

## The full picture at the end

Putting it all together, here is the complete application:

1. **Spring Boot** app with a **REST API** (`Controller → Service → Repository`)
   for both **customers** and **orders**.
2. **DynamoDB** stores customers (`Customers` table) and orders (`Orders` table).
   An order must reference an existing, immutable `customerId`.
3. On **create**, an event fans out via **SNS** to **five** SQS consumers:
   - an **Order Processor** (marks `PROCESSED`),
   - a **Lambda-like** consumer (marks `NOTIFIED`),
   - an **email** notification (SES),
   - an **SMS** notification (SNS direct publish),
   - a **shipping** processor (marks `SHIPPING`).
4. **IAM / Secrets Manager / KMS** secure it (defined as CloudFormation IaC).
5. **Redis** caches reads (cache-aside), so `GET /orders/{id}` is fast.

The recurring patterns you should now recognize:

- **Dependency injection** — every class declares what it needs in its
  constructor; Spring wires it together.
- **Layering** — Controller (HTTP) → Service (logic) → Repository (data).
- **`endpointOverride`** — the trick that lets the *same code* run against local
  emulators during development and real AWS in production.
- **Least privilege + best effort** — narrow IAM permissions, and messaging that
  never breaks the main flow.
- **Annotations** (`@Service`, `@Cacheable`, `@Scheduled`, …) — Spring's way of
  adding behavior without boilerplate.

To run everything locally:

```bash
docker compose up -d     # DynamoDB Local + LocalStack + Redis
gradlew.bat bootRun      # the app (Windows: gradlew.bat; Linux/macOS: ./gradlew)
```

Then open the Swagger UI at http://localhost:8080/swagger-ui/index.html and try
creating and reading orders.


