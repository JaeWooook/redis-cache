package demo_cache.redis.controller;

import demo_cache.redis.entity.Product;
import demo_cache.redis.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public ResponseEntity<List<Product>> findAll() {
        return ResponseEntity.ok(productService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> findById(@PathVariable Long id) {
        return ResponseEntity.ok(productService.findById(id));
    }

    @GetMapping("/category/{category}")
    public ResponseEntity<List<Product>> findByCategory(@PathVariable String category) {
        return ResponseEntity.ok(productService.findByCategory(category));
    }

    @DeleteMapping("/cache")
    public ResponseEntity<Void> evictAll() {
        productService.evictAll();
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/cache/{key}")
    public ResponseEntity<Void> evict(@PathVariable String key) {
        productService.evict(key);
        return ResponseEntity.noContent().build();
    }
}
