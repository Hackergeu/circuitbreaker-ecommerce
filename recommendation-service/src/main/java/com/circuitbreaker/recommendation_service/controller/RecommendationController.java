package com.circuitbreaker.recommendation_service.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class RecommendationController {

    @GetMapping("/recommendations")
    public List<Map<String, Object>> getRecommendations() {
        return List.of(
                Map.of("productId", 1, "reason", "Frequently bought together"),
                Map.of("productId", 2, "reason", "Trending this week")
        );
    }

    @GetMapping("/recommendations/slow")
    public List<Map<String, Object>> getSlowRecommendations() throws InterruptedException {
        Thread.sleep(5000); // simulates a slow/overloaded service — used later to trip the circuit breaker
        return getRecommendations();
    }
}