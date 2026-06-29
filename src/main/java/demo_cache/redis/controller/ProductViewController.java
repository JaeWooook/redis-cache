package demo_cache.redis.controller;

import demo_cache.redis.entity.Product;
import demo_cache.redis.service.ProductRedissonService;
import demo_cache.redis.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductViewController {

    private final ProductService productService;
    private final ProductRedissonService productRedissonService;

    @GetMapping
    public String index(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Long id,
            @RequestParam(defaultValue = "lettuce") String mode,
            Model model) {

        long start = System.currentTimeMillis();

        List<Product> products;
        String queryInfo;

        boolean isRedisson = "redisson".equals(mode);

        if (id != null) {
            Product product = isRedisson
                    ? productRedissonService.findById(id)
                    : productService.findById(id);
            products = List.of(product);
            queryInfo = "단건 조회 (id=" + id + ")";
        } else if (category != null && !category.isBlank()) {
            products = isRedisson
                    ? productRedissonService.findByCategory(category)
                    : productService.findByCategory(category);
            queryInfo = "카테고리 조회 (" + category + ")";
        } else {
            products = isRedisson
                    ? productRedissonService.findAll()
                    : productService.findAll();
            queryInfo = "전체 조회";
        }

        long elapsed = System.currentTimeMillis() - start;

        model.addAttribute("products", products);
        model.addAttribute("queryInfo", queryInfo);
        model.addAttribute("elapsed", elapsed);
        model.addAttribute("mode", mode);

        return "products";
    }

    @PostMapping("/cache/evict")
    public String evictAll(@RequestParam(defaultValue = "lettuce") String mode) {
        if ("redisson".equals(mode)) {
            productRedissonService.evictAll();
        } else {
            productService.evictAll();
        }
        return "redirect:/products?mode=" + mode;
    }
}
