# CircuitBreaker: Cloud-Native E-Commerce API Gateway

A microservices system demonstrating cloud-native resilience patterns — service discovery, API gateway routing, and all four core Resilience4j patterns (Circuit Breaker, Timeout, Bulkhead, Rate Limiter) — built with Spring Boot, Spring Cloud Gateway, and Resilience4j.

## Problem Statement

In a microservices architecture, if one service (e.g. a Recommendation Engine) becomes slow or unresponsive, services that depend on it can hang indefinitely, consuming threads and potentially cascading into a full system failure. This project implements multiple resilience patterns at the API Gateway level to detect failing downstream services, protect system resources, and fail fast with graceful fallbacks — instead of letting failures cascade.

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
                          ┌────────────┴────────────┐
                          │                          │
                 Client Requests          ┌───────────┴───────────┐
                                          │    Admin Server         │
                                          │  Port: 9090              │
                                          │  Auto-discovers all      │
                                          │  services via Eureka     │
                                          └──────────────────────────┘
```

Additionally, `api-gateway` serves a live **circuit breaker state visualization dashboard** at `/dashboard.html`, polling `/actuator/circuitbreakers` every 3 seconds and displaying a color-coded status indicator (Green = Closed, Yellow = Half-Open, Red = Open).

## Services

| Service | Port | Responsibility |
|---|---|---|
| `eureka-server` | 8761 | Service registry — all services register here and discover each other by name |
| `product-service` | 8081 | Returns mock product catalog data |
| `inventory-service` | 8082 | Returns mock stock level data |
| `recommendation-service` | 8083 | Returns mock recommendation data; includes a `/recommendations/slow` endpoint that simulates a 5-second delay, used to deliberately trip the circuit breaker |
| `api-gateway` | 8080 | Single entry point for all client requests; routes to backend services via Eureka-based load balancing; implements all four resilience patterns below; serves the live status dashboard |
| `admin-server` | 9090 | Spring Boot Admin — auto-discovers and monitors all registered services via Eureka, showing UP/DOWN status and health/metrics for each |

## Resilience Patterns Implemented

All four patterns named in the original project spec are implemented and verified working, not just configured on paper.

### 1. Circuit Breaker

Wraps the route to `recommendation-service`. Tracks the last 10 requests; if 3+ have been made and the failure rate crosses 50%, the circuit **opens** and all further requests are immediately routed to a static fallback response — no waiting, no cascading failure.

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

```yaml
  timelimiter:
    instances:
      recommendationCircuitBreaker:
        timeout-duration: 2s
```

### 3. Bulkhead

Limits `recommendation-service` to a maximum of 5 concurrent in-flight requests through the gateway, preventing one overloaded service from consuming all available threads and starving the rest of the system.

```yaml
  bulkhead:
    instances:
      recommendationCircuitBreaker:
        max-concurrent-calls: 5
        max-wait-duration: 2s
```

### 4. Rate Limiter

A global filter applied to **every** route through the gateway, capping traffic at 5 requests per 10-second window. Requests beyond that limit receive an immediate `429 Too Many Requests` instead of being processed — protecting the gateway itself from being overwhelmed (scraping, accidental retry storms, etc.), independent of any single downstream service's health.

Implemented as a custom `GlobalFilter` (`RateLimitingGlobalFilter.java`) using Resilience4j's reactive `RateLimiterOperator`, since Spring Cloud Gateway's built-in rate limiter requires Redis, which was intentionally avoided to keep infrastructure minimal for this project's scope.

## Live State Visualization

`http://localhost:8080/dashboard.html` — a self-contained HTML/JS page (no build tooling required) that polls the gateway's circuit breaker state every 3 seconds and displays it as a color-coded indicator:

- 🟢 **Green (CLOSED)** — healthy, requests flowing normally
- 🟡 **Yellow (HALF_OPEN)** — recovering, sending test requests to check if the service is back
- 🔴 **Red (OPEN)** — tripped, all requests served from fallback

Verified through a full live cycle: tripped from CLOSED → OPEN by hammering `/recommendations/slow`, observed the automatic transition to HALF_OPEN after the cooldown period, and confirmed recovery back to CLOSED by sending successful requests to `/recommendations` during the half-open window.

## Monitoring

Two complementary monitoring views are provided:

1. **Spring Boot Admin** (`localhost:9090`) — general application health, uptime, and metrics across all 5 services, auto-discovered via Eureka (no client dependency needed in each service beyond Actuator)
2. **Custom dashboard** (`localhost:8080/dashboard.html`) — focused, real-time circuit breaker state specifically, with live metrics (failure rate, buffered calls, slow call rate)

Circuit breaker state can also be queried directly via API at any time:
```
http://localhost:8080/actuator/circuitbreakers
```

## Setup Instructions

### Prerequisites
- Java 17+
- Maven (or use the included `mvnw` wrapper)

### Running locally

Clone the repository and start each service in the following order (order matters — services need Eureka available before they can register, and Admin Server needs the others registered before it can display them):

```bash
git clone https://github.com/Hackergeu/circuitbreaker-ecommerce.git
cd circuitbreaker-ecommerce
```

1. **eureka-server** — start first, wait for it to fully boot
2. **product-service**
3. **inventory-service**
4. **recommendation-service**
5. **api-gateway**
6. **admin-server** — start last, since it discovers the others

Each service can be run from IntelliJ (right-click the `*Application.java` file → Run) or via Maven from each module's directory:

```bash
./mvnw spring-boot:run
```

### Verifying it works

- `http://localhost:8761` — confirm all services show `UP` on the Eureka dashboard
- `http://localhost:9090` — confirm Spring Boot Admin shows all 5 applications as `UP`
- Test routes through the gateway:
  - `http://localhost:8080/products`
  - `http://localhost:8080/inventory`
  - `http://localhost:8080/recommendations`
- `http://localhost:8080/dashboard.html` — should show a green `CLOSED` indicator
- Test the circuit breaker: hit `http://localhost:8080/recommendations/slow` 3-4 times in a row and watch the dashboard flip to red
- Test rate limiting: hit `http://localhost:8080/products` 6+ times rapidly within 10 seconds — later requests should return `429 Too Many Requests`

## Known Issues

- **`/actuator/health` circuit breaker gap:** on Spring Boot 4.x milestone/early releases, circuit breaker state does not appear under `/actuator/health`, even with `management.health.circuitbreakers.enabled: true` set. This is a confirmed upstream issue ([resilience4j/resilience4j#2350](https://github.com/resilience4j/resilience4j/issues/2350)), not a configuration problem in this project. Circuit breaker state is reliably verified via `/actuator/circuitbreakers` instead, which provides more detail anyway.
- **Windows local hostname resolution:** by default, Eureka registers services using the machine's Windows network hostname (e.g. `LAPTOP-XXXX.mshome.net`), which Spring Cloud Gateway's Netty-based DNS resolver cannot resolve, causing `UnknownHostException` on routing. Fixed by setting `eureka.instance.prefer-ip-address: true` on every service, including the gateway itself.
- **Spring Cloud Gateway property rename:** as of Spring Cloud 2025.1 (Oakwood), the reactive gateway route configuration key changed from `spring.cloud.gateway.routes` to `spring.cloud.gateway.server.webflux.routes`. The old key is silently ignored rather than throwing an error, which manifests as routes returning 404 with no obvious cause.

## Tech Stack

- **Java 17**, **Spring Boot 4.1**
- **Spring Cloud Gateway** (Reactive / WebFlux-based) — API gateway and routing
- **Netflix Eureka** — service discovery and registration
- **Resilience4j** — circuit breaker, timeout, bulkhead, and rate limiter
- **Spring Boot Admin** — service monitoring dashboard
- **Spring Boot Actuator** — health checks and circuit breaker metrics
- **Vanilla HTML/CSS/JS** — live circuit breaker state visualization (no build tooling)
- **Maven** — build and dependency management

## Project Structure

```
circuitbreaker-ecommerce/
├── eureka-server/          # Service registry
├── product-service/        # Mock product catalog API
├── inventory-service/      # Mock stock levels API
├── recommendation-service/ # Mock recommendations API + simulated latency endpoint
├── api-gateway/            # Gateway + all 4 resilience patterns + live dashboard
│   └── src/main/resources/static/dashboard.html
├── admin-server/           # Spring Boot Admin, auto-discovers via Eureka
├── CircuitBreaker-Ecommerce.postman_collection.json
└── README.md
```

## What's Not Included (Honest Scope Note)

This project deliberately focused on the backend resilience implementation over the full original spec's frontend track. The following from the original project document were not implemented:

- Distributed Tracing (Micrometer/Zipkin)
- A dedicated React frontend (substituted with Spring Boot Admin + a custom lightweight dashboard, which achieve the same monitoring/visualization goals with less overhead)
- A "Trigger Latency" UI button (substituted with a hardcoded `/recommendations/slow` endpoint for the same demo effect)

The core deliverable — the circuit breaker pattern and surrounding resilience mechanisms — is fully implemented, tested, and verified working end-to-end.