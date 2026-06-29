package demo_cache.redis.service;

import demo_cache.redis.entity.Product;
import demo_cache.redis.repository.ProductRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redisson 기반 고트래픽 캐시 서비스
 *
 * 핵심 문제: Cache Stampede
 *   - 캐시 만료 순간 동시 요청 N개가 모두 DB를 조회 → DB 과부하
 *
 * 해결 패턴: Double-Check Locking with RLock
 *   1. 캐시 조회 (1차 체크)
 *   2. MISS → 분산 락 획득 시도
 *   3. 락 획득 후 캐시 재조회 (2차 체크 — 다른 스레드가 이미 갱신했을 수 있음)
 *   4. 여전히 MISS → DB 조회 후 캐시 저장
 *   5. 락 대기 중이던 나머지 요청들은 2차 체크에서 HIT 처리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductRedissonService {

    private static final String CACHE_NAME   = "product:rmap";
    private static final String LOCK_PREFIX  = "lock:product:";
    private static final long   TTL_MINUTES  = 10;
    private static final long   LOCK_WAIT    = 5;   // 락 획득 대기 (초)
    private static final long   LOCK_LEASE   = 10;  // 락 자동 해제 (초)

    private final ProductRepository productRepository;
    private final RedissonClient redissonClient;

    private RMapCache<String, Object> cache;

    @PostConstruct
    public void init() {
        cache = redissonClient.getMapCache(CACHE_NAME);
    }

    /** 전체 상품 조회 */
    public List<Product> findAll() {
        return getWithLock("all", () -> productRepository.findAll());
    }

    /** 단건 상품 조회 */
    public Product findById(Long id) {
        return (Product) getWithLock("id:" + id, () ->
                productRepository.findById(id)
                        .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 상품입니다. id=" + id))
        );
    }

    /** 카테고리별 상품 조회 */
    public List<Product> findByCategory(String category) {
        return getWithLock("category:" + category, () -> productRepository.findByCategory(category));
    }

    /** 전체 캐시 삭제 */
    public void evictAll() {
        cache.clear();
        log.info("[REDISSON EVICT ALL] RMapCache '{}' 전체 삭제", CACHE_NAME);
    }

    /**
     * Double-Check Locking 패턴 공통 메서드
     * - 1차 캐시 조회 → MISS 시 분산 락 획득 → 2차 캐시 조회 → MISS 시 DB 조회
     */
    private <T> T getWithLock(String key, DbSupplier<T> dbQuery) {
        // 1차 캐시 조회
        Object cached = cache.get(key);
        if (cached != null) {
            log.info("[REDISSON HIT] key={}", key);
            return (T) cached;
        }

        // MISS → 분산 락 획득 시도
        RLock lock = redissonClient.getLock(LOCK_PREFIX + key);
        try {
            boolean acquired = lock.tryLock(LOCK_WAIT, LOCK_LEASE, TimeUnit.SECONDS);
            if (acquired) {
                try {
                    // 2차 캐시 조회 (락 대기 중 다른 스레드가 이미 갱신했을 수 있음)
                    cached = cache.get(key);
                    if (cached != null) {
                        log.info("[REDISSON HIT after lock] key={} (다른 스레드가 이미 갱신)", key);
                        return (T) cached;
                    }

                    // DB 조회 후 캐시 저장
                    log.info("[REDISSON MISS] key={} → DB 조회", key);
                    T result = dbQuery.get();
                    cache.put(key, result, TTL_MINUTES, TimeUnit.MINUTES);
                    return result;

                } finally {
                    lock.unlock();
                }
            } else {
                // 락 획득 실패 → 캐시 재조회 (락 보유 스레드가 갱신 완료했을 가능성 높음)
                log.warn("[REDISSON LOCK TIMEOUT] key={} → 캐시 재조회 fallback", key);
                cached = cache.get(key);
                if (cached != null) {
                    return (T) cached;
                }
                return dbQuery.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[REDISSON LOCK INTERRUPTED] key={}", key);
            return dbQuery.get();
        }
    }

    @FunctionalInterface
    private interface DbSupplier<T> {
        T get();
    }
}
