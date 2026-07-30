package com.circuitbreaker.api_gateway.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class FallbackController {

    @GetMapping("/fallback/recommendations")
    public List<Map<String, Object>> recommendationsFallback() {
        return List.of(
                Map.of("productId", 1, "reason", "Top Seller (fallback data)"),
                Map.of("productId", 2, "reason", "Top Seller (fallback data)")
        );
    }
}