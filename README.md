# CircuitBreaker: Cloud-Native E-Commerce API Gateway

A microservices system demonstrating cloud-native resilience patterns — service discovery, API gateway routing, and circuit breaker fault tolerance — built with Spring Boot, Spring Cloud Gateway, and Resilience4j.

## Problem Statement

In a microservices architecture, if one service (e.g. a Recommendation Engine) becomes slow or unresponsive, services that depend on it can hang indefinitely, consuming threads and potentially cascading into a full system failure. This project implements the **Circuit Breaker pattern** to detect failing downstream services and fail fast with a graceful fallback response, instead of letting the failure cascade.

## Architecture

```
                         ┌─────────────────────┐
                         │   Eureka Server      │
                         │   (Service Registry)  │
                         │   Port: 8761          │
                         └──────────▲───────────┘
                                    │ registers with
              ┌─────────────────────┼─────────────────────┐
              │                     │                      │
     ┌────────┴────────┐  ┌─────────┴────────┐  ┌──────────┴─────────┐
     │ product-service  │  │ inventory-service │  │ recommendation-    │
     │ Port: 8081        │  │ Port: 8082        │  │ service            │
     │                   │  │                   │  │ Port: 8083         │
     └────────▲──────────┘  └─────────▲─────────┘  └──────────▲─────────┘
              │                       │                        │
              └───────────────────────┼────────────────────────┘
                                       │ routes via lb://
                          ┌────────────┴─────────────┐
                          │       API Gateway          │
                          │  (Spring Cloud Gateway)     │
                          │       Port: 8080            │
                          │                             │
                          │  Resilience4j Circuit       │
                          │  Breaker on recommendation-  │
                          │  service route              │
                          └─────────────────────────────┘
                                       │
                                  Client Requests
```

## Services

| Service | Port | Responsibility |
|---|---|---|
| `eureka-server` | 8761 | Service registry — all services register here and discover each other by name |
| `product-service` | 8081 | Returns mock product catalog data |
| `inventory-service` | 8082 | Returns mock stock level data |
| `recommendation-service` | 8083 | Returns mock recommendation data; includes a `/recommendations/slow` endpoint that simulates a 5-second delay, used to deliberately trip the circuit breaker |
| `api-gateway` | 8080 | Single entry point for all client requests; routes to backend services via Eureka-based load balancing; wraps the recommendation route with a Resilience4j circuit breaker and fallback |

## The Core Feature: Circuit Breaker in Action

The Gateway's route to `recommendation-service` is protected by a Resilience4j circuit breaker with a 2-second timeout. Under normal conditions, requests pass through and return real data. When the service is slow or failing:

1. The circuit breaker tracks the last 10 requests in a sliding window
2. If 50% or more fail (including timeouts), the circuit **opens**
3. While open, all requests are immediately routed to a fallback endpoint returning cached "Top Sellers" data — no waiting, no error shown to the client
4. After a 10-second cooldown, the circuit moves to **half-open** and allows a few test requests through to check if the service has recovered

This was verified by repeatedly hitting the `/recommendations/slow` endpoint (which always takes 5 seconds — longer than the configured 2-second timeout) through the gateway and confirming:
- Initial requests failed on timeout
- After the failure threshold was hit, `/actuator/circuitbreakers` showed `"state": "OPEN"`
- Subsequent requests returned the fallback response instantly, with no delay

### Resilience4j Configuration

```yaml
resilience4j:
  circuitbreaker:
    instances:
      recommendationCircuitBreaker:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
  timelimiter:
    instances:
      recommendationCircuitBreaker:
        timeout-duration: 2s
```

## Setup Instructions

### Prerequisites
- Java 17+
- Maven (or use the included `mvnw` wrapper)

### Running locally

Clone the repository and start each service in the following order (order matters — services need Eureka available before they can register):

```bash
git clone https://github.com/Hackergeu/circuitbreaker-ecommerce.git
cd circuitbreaker-ecommerce
```

1. **eureka-server** — start first, wait for it to fully boot
2. **product-service**
3. **inventory-service**
4. **recommendation-service**
5. **api-gateway** — start last

Each service can be run from IntelliJ (right-click the `*Application.java` file → Run) or via Maven from each module's directory:

```bash
./mvnw spring-boot:run
```

### Verifying it works

- Open `http://localhost:8761` — confirm all four services show as `UP`
- Test routes through the gateway:
    - `http://localhost:8080/products`
    - `http://localhost:8080/inventory`
    - `http://localhost:8080/recommendations`
- Test the circuit breaker: repeatedly hit `http://localhost:8080/recommendations/slow` and observe the response switch from a ~2-second delay to an instant fallback response
- Check circuit breaker state directly: `http://localhost:8080/actuator/circuitbreakers`

## Known Issue

`/actuator/health` does not currently show circuit breaker state on Spring Boot 4.x milestone releases — this is a confirmed upstream issue ([resilience4j/resilience4j#2350](https://github.com/resilience4j/resilience4j/issues/2350)), not a configuration problem in this project. Circuit breaker state is reliably verified via the dedicated `http://localhost:8080/actuator/circuitbreakers` endpoint instead, which provides more detailed metrics (failure rate, buffered calls, slow call rate) than the health endpoint would anyway.

## Tech Stack

- **Java 17**, **Spring Boot 4.1**
- **Spring Cloud Gateway** (Reactive / WebFlux-based) — API gateway and routing
- **Netflix Eureka** — service discovery and registration
- **Resilience4j** — circuit breaker and timeout handling
- **Spring Boot Actuator** — health checks and circuit breaker monitoring
- **Maven** — build and dependency management

## Project Structure

```
circuitbreaker-ecommerce/
├── eureka-server/          # Service registry
├── product-service/        # Mock product catalog API
├── inventory-service/      # Mock stock levels API
├── recommendation-service/ # Mock recommendations API + simulated latency endpoint
├── api-gateway/            # Spring Cloud Gateway + Resilience4j circuit breaker
└── README.md
```