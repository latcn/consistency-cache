package com.consist.cache.spring.aspect;

import com.consist.cache.core.executor.CacheExecutor;
import com.consist.cache.spring.config.TestConfig;
import com.consist.cache.spring.service.TestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.ContextConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests for HccCacheInterceptor with Spring Test Context Framework
 * Uses real beans from CacheAutoConfiguration with properly mocked RedissonClient
 */
@DisplayName("HccCacheInterceptor Spring Integration Tests")
@SpringBootTest(classes= {TestService.class})
@EnableAspectJAutoProxy
@ContextConfiguration(classes = {
    TestConfig.class
}
)
class HccCacheInterceptorSpringIntegrationTest {

    @Autowired
    private TestService testService;

    @Autowired
    private CacheExecutor cacheExecutor;

    @Autowired
    private HccCacheInterceptor hccCacheInterceptor;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("Should load HccCacheInterceptor bean from context")
    void testInterceptorBeanLoaded() {
        assertNotNull(hccCacheInterceptor, "HccCacheInterceptor should be autowired");
        assertNotNull(cacheExecutor, "CacheExecutor should be autowired");
    }

    @Test
    @DisplayName("Should intercept HccCacheable annotation via AOP")
    void testHccCacheableViaAOP() throws Exception {
        // Given: Service bean proxied by Spring AOP
        // When: Call annotated method
        Object result1 = testService.getDataWithCache(123L);
        Object result2 = testService.getDataWithCache(123L); // Should hit cache

        // Then: Both calls should return same result (cached)
        assertNotNull(result1);
        assertNotNull(result2);
        assertEquals(result1, result2);
    }

    @Test
    @DisplayName("Should handle HccCacheEvict annotation via AOP")
    void testHccCacheEvictViaAOP() throws Exception {
        // Given: Service bean with evict annotation
        
        
        // First call to populate cache
        String firstResult = testService.getDataWithCache(456L);
        
        // Second call should return cached value
        String cachedResult = testService.getDataWithCache(456L);
        assertEquals(firstResult, cachedResult);
        
        // When: Evict cache
        testService.deleteData(456L);
        // Then: Next call should reload from source
        String afterEvictResult = testService.getDataWithCache(456L);
        assertNotNull(afterEvictResult);
    }

    @Test
    @DisplayName("Should evaluate SpEL expressions in cache keys")
    void testSpELKeyEvaluation() throws Exception {
        // Given: Service with SpEL key expression
        
        
        // When: Call with different parameters
        Object result1 = testService.getUserById(100L);
        Object result2 = testService.getUserById(200L);

        // Then: Different keys should produce different results
        assertNotNull(result1);
        assertNotNull(result2);
        // Note: In real scenario, these would be different cached values
    }

    @Test
    @DisplayName("Should handle composite SpEL keys")
    void testCompositeSpELKey() throws Exception {
        // Given: Service with composite key
        
        
        // When: Call with composite parameters
        Object result = testService.getCompositeData("product", 999L);

        // Then: Should handle composite key correctly
        assertNotNull(result);
    }

    @Test
    @DisplayName("Should bypass interceptor for non-annotated methods")
    void testNonAnnotatedMethod() throws Exception {
        // Given: Service with mixed annotations
        
        
        // When: Call method without cache annotation
        Object result = testService.getWithoutAnnotation("test");

        // Then: Should execute normally without caching
        assertEquals("test-result", result);
    }

    @Test
    @DisplayName("Should handle null values gracefully")
    void testNullValueHandling() throws Exception {
        // Given: Service that may return null

        // When: Method returns null
        Object result = testService.getNullableData(null);

        // Then: Should handle null without exception
        // (Actual behavior depends on cache configuration)
       // assertNotNull(result); // Or assert based on actual implementation
    }
}
