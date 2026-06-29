package demo_cache.redis.controller;

import demo_cache.redis.entity.Product;
import demo_cache.redis.service.ProductRedissonService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/products")
@RequiredArgsConstructor
public class ProductRedissonController {

    private final ProductRedissonService productRedissonService;

    @GetMapping
    public ResponseEntity<List<Product>> findAll() {
        return ResponseEntity.ok(productRedissonService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> findById(@PathVariable Long id) {
        return ResponseEntity.ok(productRedissonService.findById(id));
    }

    @GetMapping("/category/{category}")
    public ResponseEntity<List<Product>> findByCategory(@PathVariable String category) {
        return ResponseEntity.ok(productRedissonService.findByCategory(category));
    }

    @DeleteMapping("/cache")
    public ResponseEntity<Void> evictAll() {
        productRedissonService.evictAll();
        return ResponseEntity.noContent().build();
    }
}
