# CircuitBreaker: Cloud-Native E-Commerce API Gateway

A complete microservices system demonstrating cloud-native resilience patterns — service discovery, API gateway routing, all four core Resilience4j patterns (Circuit Breaker, Timeout, Bulkhead, Rate Limiter), live monitoring, and distributed tracing — built with Spring Boot, Spring Cloud Gateway, Resilience4j, and Zipkin.

## Problem Statement

In a microservices architecture, if one service (e.g. a Recommendation Engine) becomes slow or unresponsive, services that depend on it can hang indefinitely, consuming threads and potentially cascading into a full system failure. This project implements multiple resilience patterns at the API Gateway level to detect failing downstream services, protect system resources, and fail fast with graceful fallbacks — instead of letting failures cascade. It also implements distributed tracing so that a single request's journey across multiple services can be followed end-to-end.

## Architecture

```
                         ┌─────────────────────┐
                         │   Eureka Server      │
                         │   (Service Registry)  │
                         │   Port: 8761          │
                         └──────────▲───────────┘
                                    │ registers with
        ┌───────────────────────────┼───────────────────────────┐
        │                           │                           │
┌───────┴────────┐  ┌───────────────┴──────┐  ┌──────────────────┴───────┐
│ product-service  │  │ inventory-service    │  │ recommendation-service    │
│ Port: 8081        │  │ Port: 8082            │  │ Port: 8083                 │
└───────▲────────┘  └───────────────▲──────┘  └──────────────────▲───────┘
        │                           │                              │
        └───────────────────────────┼──────────────────────────────┘
                                     │ routes via lb://
                        ┌────────────┴─────────────┐
                        │       API Gateway          │
                        │  (Spring Cloud Gateway)     │
                        │       Port: 8080            │
                        │                             │
                        │  • Circuit Breaker           │
                        │  • Timeout (TimeLimiter)     │
                        │  • Bulkhead                  │
                        │  • Rate Limiter (global)     │
                        └──────────────┬──────────────┘
                                       │
                ┌──────────────────────┼──────────────────────┐
                │                      │                      │
       Client Requests      ┌──────────┴──────────┐  ┌────────┴────────┐
                             │    Admin Server       │  │  Zipkin Server   │
                             │  Port: 9090            │  │  Port: 9411       │
                             │  Auto-discovers all    │  │  Collects traces   │
                             │  services via Eureka   │  │  from all 4         │
                             └────────────────────────┘  │  request-path        │
                                                          │  services            │
                                                          └─────────────────────┘
```

Every service in the request path (`api-gateway`, `product-service`, `inventory-service`, `recommendation-service`) reports trace spans to Zipkin, so a single incoming request can be followed as it hops from the Gateway into a backend service and back.

`api-gateway` also serves a live **circuit breaker state visualization dashboard** at `/dashboard.html`, polling `/actuator/circuitbreakers` every 3 seconds, with a one-click **Trigger Latency** button that fires a burst of requests at the deliberately slow endpoint to demonstrate the circuit tripping in real time.

## Services

| Service | Port | Responsibility |
|---|---|---|
| `eureka-server` | 8761 | Service registry — all services register here and discover each other by name |
| `product-service` | 8081 | Returns mock product catalog data |
| `inventory-service` | 8082 | Returns mock stock level data |
| `recommendation-service` | 8083 | Returns mock recommendation data; includes a `/recommendations/slow` endpoint that simulates a 5-second delay, used to deliberately trip the circuit breaker |
| `api-gateway` | 8080 | Single entry point for all client requests; routes to backend services via Eureka-based load balancing; implements all four resilience patterns; serves the live status dashboard |
| `admin-server` | 9090 | Spring Boot Admin — auto-discovers and monitors all registered services via Eureka |
| Zipkin (external) | 9411 | Standalone distributed tracing server; collects and visualizes request traces across services |

## Resilience Patterns Implemented

All four patterns named in the original project spec are implemented and verified working, not just configured on paper.

### 1. Circuit Breaker

Wraps the route to `recommendation-service`. Tracks the last 10 requests; once at least 3 have been made and the failure rate crosses 50%, the circuit **opens** and all further requests are immediately routed to a static fallback response — no waiting, no cascading failure.

```yaml
resilience4j:
  circuitbreaker:
    instances:
      recommendationCircuitBreaker:
        sliding-window-size: 10
        minimum-number-of-calls: 3
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
```

**Note on `minimum-number-of-calls`:** this defaults to 100 in Resilience4j if left unset, which combined with a sliding window of 10 means the circuit could never mathematically gather enough data to trip. It's explicitly set to 3 here so the breaker responds quickly and predictably.

### 2. Timeout (TimeLimiter)

Any call to `recommendation-service` taking longer than 2 seconds is treated as a failure — this is what allows the deliberately slow `/recommendations/slow` endpoint (5s delay) to trip the breaker.

### 3. Bulkhead

Limits `recommendation-service` to a maximum of 5 concurrent in-flight requests through the gateway, preventing one overloaded service from consuming all available threads and starving the rest of the system.

### 4. Rate Limiter

A global filter applied to **every** route through the gateway, capping traffic at 5 requests per 10-second window. Requests beyond that limit receive an immediate `429 Too Many Requests`. Implemented as a custom `GlobalFilter` using Resilience4j's reactive `RateLimiterOperator`, since Spring Cloud Gateway's built-in rate limiter requires Redis, which was intentionally avoided to keep infrastructure minimal.

## Distributed Tracing

Implemented with **Micrometer Tracing** (Brave bridge) reporting to a standalone **Zipkin** server. Every service in the request path is instrumented, so a single request through the Gateway produces a multi-span trace showing:

- Total end-to-end request duration
- Time spent in the Gateway itself
- Time spent in the downstream service handling the actual work
- Success/failure outcome and HTTP metadata per span

```yaml
management:
  tracing:
    sampling:
      probability: 1.0
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans
```

`sampling.probability: 1.0` traces 100% of requests, appropriate for development/demo purposes (production systems typically sample a smaller percentage to reduce overhead).

View traces at `http://localhost:9411` after generating some traffic through the gateway.

## Live State Visualization

`http://localhost:8080/dashboard.html` — a self-contained HTML/JS page (no build tooling required) that polls the gateway's circuit breaker state every 3 seconds and displays it as a color-coded indicator:

- 🟢 **Green (CLOSED)** — healthy, requests flowing normally
- 🟡 **Yellow (HALF_OPEN)** — recovering, sending test requests to check if the service is back
- 🔴 **Red (OPEN)** — tripped, all requests served from fallback

A **⚡ Trigger Latency** button on the dashboard fires a burst of requests directly at `/recommendations/slow`, so the full trip → fallback → recovery cycle can be demonstrated with a single click, no external tool needed.

## Monitoring

Three complementary observability views are provided:

1. **Spring Boot Admin** (`localhost:9090`) — general application health, uptime, and metrics across all services, auto-discovered via Eureka
2. **Custom dashboard** (`localhost:8080/dashboard.html`) — focused, real-time circuit breaker state with live metrics
3. **Zipkin** (`localhost:9411`) — distributed request tracing across all services

Circuit breaker state can also be queried directly via API:
```
http://localhost:8080/actuator/circuitbreakers
```

## Setup Instructions

### Prerequisites
- Java 17+
- Maven (or use the included `mvnw` wrapper)

### Running locally

```bash
git clone https://github.com/Hackergeu/circuitbreaker-ecommerce.git
cd circuitbreaker-ecommerce
```

Start components in this order (order matters — services need Eureka available before they can register, and Zipkin should be running before the others start so tracing connects cleanly):

1. **Zipkin server** (standalone jar, not part of this repo):
   ```bash
   java -jar zipkin-server.jar
   ```
2. **eureka-server**
3. **product-service**
4. **inventory-service**
5. **recommendation-service**
6. **api-gateway**
7. **admin-server** — start last, since it discovers the others

Each Spring Boot service can be run from IntelliJ (right-click the `*Application.java` file → Run) or via Maven:
```bash
./mvnw spring-boot:run
```

### Verifying it works

- `http://localhost:8761` — all services show `UP` on Eureka
- `http://localhost:9090` — Spring Boot Admin shows all applications `UP`
- `http://localhost:8080/products`, `/inventory`, `/recommendations` — all return data
- `http://localhost:8080/dashboard.html` — shows green `CLOSED`; click **Trigger Latency** to watch it flip to red then recover
- `http://localhost:9411` — after generating some traffic, click "Run Query" to see traces with 2+ spans each

## Known Issues

- **`/actuator/health` circuit breaker gap:** on Spring Boot 4.x milestone/early releases, circuit breaker state does not appear under `/actuator/health`. Confirmed upstream issue ([resilience4j/resilience4j#2350](https://github.com/resilience4j/resilience4j/issues/2350)). Verified via `/actuator/circuitbreakers` instead.
- **Windows local hostname resolution:** Eureka registers services using the Windows network hostname by default, which Spring Cloud Gateway's Netty DNS resolver cannot resolve. Fixed with `eureka.instance.prefer-ip-address: true` on every service.
- **Spring Cloud Gateway property rename:** as of Spring Cloud 2025.1 (Oakwood), the reactive gateway route configuration key changed from `spring.cloud.gateway.routes` to `spring.cloud.gateway.server.webflux.routes`. The old key is silently ignored rather than throwing an error.
- **Zipkin starter naming:** on Spring Boot 4.x, the correct dependency is `spring-boot-starter-zipkin` (bundles the Brave bridge and reporter together) rather than the separately-added `micrometer-tracing-bridge-brave` + `zipkin-reporter-brave` combination shown in most older tutorials.

## Tech Stack

- **Java 17**, **Spring Boot 4.1**
- **Spring Cloud Gateway** (Reactive / WebFlux-based) — API gateway and routing
- **Netflix Eureka** — service discovery and registration
- **Resilience4j** — circuit breaker, timeout, bulkhead, and rate limiter
- **Micrometer Tracing + Zipkin** — distributed request tracing
- **Spring Boot Admin** — service monitoring dashboard
- **Spring Boot Actuator** — health checks and circuit breaker metrics
- **Vanilla HTML/CSS/JS** — live circuit breaker state visualization (no build tooling)
- **Maven** — build and dependency management

## Project Structure

```
circuitbreaker-ecommerce/
├── eureka-server/          # Service registry
├── product-service/        # Mock product catalog API, tracing-instrumented
├── inventory-service/      # Mock stock levels API, tracing-instrumented
├── recommendation-service/ # Mock recommendations API + simulated latency endpoint, tracing-instrumented
├── api-gateway/            # Gateway + all 4 resilience patterns + live dashboard + tracing
│   └── src/main/resources/static/dashboard.html
├── admin-server/           # Spring Boot Admin, auto-discovers via Eureka
├── CircuitBreaker-Ecommerce.postman_collection.json
└── README.md
```

## Project Scope

This implementation covers the full week-by-week plan from the original project specification:

- **Week 1:** Microservices setup (Product, Inventory, Recommendation) + Eureka + Gateway scaffolding
- **Mid-Project Review:** Verified dynamic service discovery and routing; verified fallback behavior under simulated service failure
- **Week 2:** Resilience4j Circuit Breaker; monitoring dashboard (Spring Boot Admin, in place of a bespoke React UI)
- **Week 3:** Rate Limiting and Bulkhead; live circuit breaker state visualization (custom HTML/JS dashboard, in place of a React UI)
- **Week 4:** Distributed Tracing (Micrometer + Zipkin); one-click Trigger Latency demo button

**Substitution note:** the original spec allowed either a React frontend or Spring Boot Admin for monitoring, and either a bespoke chart library or similar tooling for state visualization. This implementation uses Spring Boot Admin plus a lightweight custom HTML/JS dashboard rather than a full React application, in order to keep the project backend-focused and deliverable within a tight timeline — both choices are explicitly permitted by the original spec and demonstrate the same underlying resilience concepts.
