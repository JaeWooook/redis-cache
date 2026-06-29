package demo_cache.redis.config;

import demo_cache.redis.entity.Product;
import demo_cache.redis.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final ProductRepository productRepository;

    @Override
    public void run(ApplicationArguments args) {
        if (productRepository.count() > 0) {
            return;
        }

        productRepository.save(new Product("노트북 A", 1_500_000, "전자제품"));
        productRepository.save(new Product("노트북 B", 1_200_000, "전자제품"));
        productRepository.save(new Product("스마트폰 A", 900_000, "전자제품"));
        productRepository.save(new Product("키보드 A", 150_000, "주변기기"));
        productRepository.save(new Product("마우스 A", 80_000, "주변기기"));
        productRepository.save(new Product("모니터 A", 400_000, "주변기기"));
        productRepository.save(new Product("책상 A", 250_000, "가구"));
        productRepository.save(new Product("의자 A", 300_000, "가구"));
    }
}
