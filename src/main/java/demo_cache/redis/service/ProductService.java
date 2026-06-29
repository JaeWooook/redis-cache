package demo_cache.redis.service;

import demo_cache.redis.entity.Product;
import demo_cache.redis.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private static final String KEY_ALL       = "product:all";
    private static final String KEY_ID        = "product:id:";
    private static final String KEY_CATEGORY  = "product:category:";
    private static final Duration TTL         = Duration.ofMinutes(10);

    private final ProductRepository productRepository;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;

    /** 전체 상품 조회 (캐시 우선) */
    public List<Product> findAll() {
        Object cached = redisTemplate.opsForValue().get(KEY_ALL).block();
        if (cached != null) {
            log.info("[CACHE HIT] key={}", KEY_ALL);
            return (List<Product>) cached;
        }

        log.info("[CACHE MISS] key={} → DB 조회", KEY_ALL);
        List<Product> products = productRepository.findAll();
        redisTemplate.opsForValue().set(KEY_ALL, products, TTL).block();
        return products;
    }

    /** 단건 상품 조회 (캐시 우선) */
    public Product findById(Long id) {
        String key = KEY_ID + id;
        Object cached = redisTemplate.opsForValue().get(key).block();
        if (cached != null) {
            log.info("[CACHE HIT] key={}", key);
            return (Product) cached;
        }

        log.info("[CACHE MISS] key={} → DB 조회", key);
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 상품입니다. id=" + id));
        redisTemplate.opsForValue().set(key, product, TTL).block();
        return product;
    }

    /** 카테고리별 상품 조회 (캐시 우선) */
    public List<Product> findByCategory(String category) {
        String key = KEY_CATEGORY + category;
        Object cached = redisTemplate.opsForValue().get(key).block();
        if (cached != null) {
            log.info("[CACHE HIT] key={}", key);
            return (List<Product>) cached;
        }

        log.info("[CACHE MISS] key={} → DB 조회", key);
        List<Product> products = productRepository.findByCategory(category);
        redisTemplate.opsForValue().set(key, products, TTL).block();
        return products;
    }

    /** 특정 키 캐시 삭제 */
    public void evict(String key) {
        redisTemplate.delete(key).block();
        log.info("[CACHE EVICT] key={}", key);
    }

    /** 전체 product 캐시 삭제 */
    public void evictAll() {
        redisTemplate.keys("product:*")
                .flatMap(redisTemplate::delete)
                .blockLast();
        log.info("[CACHE EVICT ALL] product:* 전체 삭제");
    }
}
