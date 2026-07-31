package com.circuitbreaker.api_gateway;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class RateLimitingGlobalFilter implements GlobalFilter, Ordered {

    private final io.github.resilience4j.ratelimiter.RateLimiter rateLimiter;

    public RateLimitingGlobalFilter() {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(5)
                .limitRefreshPeriod(Duration.ofSeconds(10))
                .timeoutDuration(Duration.ZERO)
                .build();
        this.rateLimiter = RateLimiterRegistry.of(config).rateLimiter("gatewayRateLimiter");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange)
                .transformDeferred(RateLimiterOperator.of(rateLimiter))
                .onErrorResume(throwable -> {
                    exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    return exchange.getResponse().setComplete();
                });
    }

    @Override
    public int getOrder() {
        return -1;
    }
}