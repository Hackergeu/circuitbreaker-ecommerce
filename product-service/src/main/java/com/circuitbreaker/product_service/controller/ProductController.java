package com.circuitbreaker.product_service.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class ProductController {

    @GetMapping("/products")
    public List<Map<String, Object>> getProducts() {
        return List.of(
                Map.of("id", 1, "name", "Wireless Mouse", "price", 799),
                Map.of("id", 2, "name", "Mechanical Keyboard", "price", 2999),
                Map.of("id", 3, "name", "USB-C Hub", "price", 1299)
        );
    }
}