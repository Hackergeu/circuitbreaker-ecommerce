package com.circuitbreaker.inventory_service.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class InventoryController {

    @GetMapping("/inventory")
    public List<Map<String, Object>> getInventory() {
        return List.of(
                Map.of("productId", 1, "stock", 25),
                Map.of("productId", 2, "stock", 12),
                Map.of("productId", 3, "stock", 0)
        );
    }
}