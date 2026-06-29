package demo_cache.redis;

import demo_cache.redis.repository.ProductRepository;
import demo_cache.redis.service.ProductRedissonService;
import demo_cache.redis.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static org.mockito.Mockito.mockingDetails;

/**
 * Cache Stampede 부하 테스트
 *
 * 실행 조건: MySQL(3306) + Redis(6379) 가 실행 중이어야 합니다.
 *
 * 실행 방법:
 *   ./gradlew test --tests "demo_cache.redis.CacheStampedeLoadTest"
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/redis_demo" +
                "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8",
        "spring.datasource.username=demo",
        "spring.datasource.password=demo1234",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379"
})
class CacheStampedeLoadTest {

    /** 동시 요청 스레드 수 */
    private static final int THREAD_COUNT = 30;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRedissonService productRedissonService;

    @MockitoSpyBean
    private ProductRepository productRepository;

    @BeforeEach
    void setUp() {
        // 각 테스트 전 캐시 초기화 + DB 호출 카운트 리셋
        productService.evictAll();
        productRedissonService.evictAll();
        Mockito.clearInvocations(productRepository);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 1. Lettuce: Cache Stampede 발생 확인
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[Lettuce] 캐시 만료 시 N개 스레드 → DB N회 호출 (Stampede 발생)")
    void lettuce_stampede_occurs() throws InterruptedException {
        LoadTestResult result = runConcurrent("LETTUCE", () -> productService.findAll());
        printReport("LETTUCE", result);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. Redisson: Cache Stampede 방지 확인
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[Redisson] 캐시 만료 시 N개 스레드 → DB 1회 호출 (Stampede 방지)")
    void redisson_stampede_prevented() throws InterruptedException {
        LoadTestResult result = runConcurrent("REDISSON", () -> productRedissonService.findAll());
        printReport("REDISSON", result);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3. 두 방식 연속 비교
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("[비교] Lettuce vs Redisson DB 호출 횟수 비교")
    void compare_lettuce_vs_redisson() throws InterruptedException {
        // -- Lettuce --
        productService.evictAll();
        productRedissonService.evictAll();
        Mockito.clearInvocations(productRepository);
        LoadTestResult lettuceResult = runConcurrent("LETTUCE", () -> productService.findAll());
        int lettuceDbCalls = countDbCalls("findAll");

        // -- Redisson --
        productService.evictAll();
        productRedissonService.evictAll();
        Mockito.clearInvocations(productRepository);
        LoadTestResult redissonResult = runConcurrent("REDISSON", () -> productRedissonService.findAll());
        int redissonDbCalls = countDbCalls("findAll");

        log.info("\n");
        log.info("╔══════════════════════════════════════════════════════╗");
        log.info("║              Lettuce vs Redisson 비교 결과            ║");
        log.info("╠══════════════════════════════════════════════════════╣");
        log.info("║  항목              │  Lettuce     │  Redisson        ║");
        log.info("╠══════════════════════════════════════════════════════╣");
        log.info("║  동시 스레드       │  {:>12} │  {:<16} ║", THREAD_COUNT, THREAD_COUNT);
        log.info("║  성공 수           │  {:>12} │  {:<16} ║", lettuceResult.success(), redissonResult.success());
        log.info("║  DB 조회 횟수      │  {:>12} │  {:<16} ║", lettuceDbCalls, redissonDbCalls);
        log.info("║  전체 소요시간(ms) │  {:>12} │  {:<16} ║", lettuceResult.totalMs(), redissonResult.totalMs());
        log.info("║  평균 응답시간(ms) │  {:>12.1f} │  {:<16.1f} ║", lettuceResult.avgMs(), redissonResult.avgMs());
        log.info("╚══════════════════════════════════════════════════════╝");
        log.info("  → Lettuce DB 호출: {}회  /  Redisson DB 호출: {}회", lettuceDbCalls, redissonDbCalls);
        log.info("  → Redisson이 DB 부하를 {}배 줄임\n",
                lettuceDbCalls > 0 && redissonDbCalls > 0
                        ? String.format("%.1f", (double) lettuceDbCalls / redissonDbCalls)
                        : "∞");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 공통 유틸
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * THREAD_COUNT 개의 스레드를 CountDownLatch로 동시에 출발시킨다.
     */
    private LoadTestResult runConcurrent(String label, Runnable action) throws InterruptedException {
        CountDownLatch startGun   = new CountDownLatch(1);          // 동시 출발 신호
        CountDownLatch finishLine = new CountDownLatch(THREAD_COUNT); // 전체 완료 대기
        AtomicInteger  success    = new AtomicInteger();
        AtomicInteger  failure    = new AtomicInteger();
        long[]         elapsed    = new long[THREAD_COUNT];

        for (int i = 0; i < THREAD_COUNT; i++) {
            final int idx = i;
            Thread t = new Thread(() -> {
                try {
                    startGun.await();                   // 출발 신호 대기
                    long start = System.currentTimeMillis();
                    action.run();
                    elapsed[idx] = System.currentTimeMillis() - start;
                    success.incrementAndGet();
                } catch (Exception e) {
                    log.error("[{}] 스레드 {} 오류: {}", label, idx, e.getMessage());
                    failure.incrementAndGet();
                } finally {
                    finishLine.countDown();
                }
            }, label + "-thread-" + i);
            t.start();
        }

        long wallStart = System.currentTimeMillis();
        startGun.countDown();                           // 전체 동시 출발
        boolean allDone = finishLine.await(30, TimeUnit.SECONDS);

        if (!allDone) {
            log.warn("[{}] 30초 내 완료되지 않은 스레드 있음", label);
        }

        long totalMs = System.currentTimeMillis() - wallStart;
        double avgMs = LongStream.of(elapsed).filter(e -> e > 0).average().orElse(0);

        return new LoadTestResult(success.get(), failure.get(), totalMs, avgMs);
    }

    /** ProductRepository 메서드 호출 횟수 집계 */
    private int countDbCalls(String methodName) {
        return (int) mockingDetails(productRepository)
                .getInvocations()
                .stream()
                .filter(inv -> inv.getMethod().getName().equals(methodName))
                .count();
    }

    private void printReport(String label, LoadTestResult r) {
        int dbCalls = countDbCalls("findAll");
        log.info("\n");
        log.info("========== [{}] 부하 테스트 결과 ==========", label);
        log.info("  동시 스레드       : {}개", THREAD_COUNT);
        log.info("  성공              : {}개", r.success());
        log.info("  실패              : {}개", r.failure());
        log.info("  전체 소요시간     : {} ms", r.totalMs());
        log.info("  평균 응답시간     : {:.1f} ms", r.avgMs());
        log.info("  DB findAll 호출   : {}회  ← {}",
                dbCalls,
                dbCalls <= 1 ? "정상 (Stampede 방지)" : "주의! (Stampede 발생)");
        log.info("==========================================\n");
    }

    record LoadTestResult(int success, int failure, long totalMs, double avgMs) {}
}
