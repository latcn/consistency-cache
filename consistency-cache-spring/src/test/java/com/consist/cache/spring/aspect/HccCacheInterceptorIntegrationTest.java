package com.consist.cache.spring.aspect;

import com.consist.cache.core.executor.CacheEvictHandler;
import com.consist.cache.core.executor.CacheExecutor;
import com.consist.cache.core.model.CacheKey;
import com.consist.cache.core.model.CacheValue;
import com.consist.cache.spring.annotation.HccCacheEvict;
import com.consist.cache.spring.annotation.HccCacheable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Integration tests for HccCacheInterceptor
 */
@DisplayName("HccCacheInterceptor Integration Tests")
@ExtendWith(MockitoExtension.class)
class HccCacheInterceptorIntegrationTest {

    @Mock
    private CacheExecutor cacheExecutor;
    
    @Mock
    private CacheEvictHandler cacheEvictHandler;

    private HccCacheInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new HccCacheInterceptor(cacheExecutor, cacheEvictHandler);
    }

    @Test
    @DisplayName("Should intercept HccCacheable annotation and use cache")
    void testHccCacheableInterception() throws Throwable {
        // Given
        TestService service = new TestService();
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(interceptor);
        TestService proxy = (TestService) proxyFactory.getProxy();

        CacheValue<String> cachedValue = CacheValue.<String>builder()
                .value("cached-data")
                .expireTime(System.currentTimeMillis() + 60000)
                .build();

        when(cacheExecutor.get(any(CacheKey.class), any())).thenReturn(cachedValue);

        // When
        Object result = proxy.getDataWithCache(123L);

        // Then
        assertEquals("cached-data", result);
        verify(cacheExecutor).get(any(CacheKey.class), any());
    }

    @Test
    @DisplayName("Should fallback to method execution when cache fails")
    void testCacheFailureFallback() throws Throwable {
        // Given
        TestService service = new TestService();
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(interceptor);
        TestService proxy = (TestService) proxyFactory.getProxy();

        when(cacheExecutor.get(any(CacheKey.class), any()))
                .thenThrow(new RuntimeException("Cache unavailable"));

        // When
        Object result = proxy.getDataWithCache(456L);

        // Then - Should return actual method result despite cache failure
        assertEquals("actual-data-456", result);
    }

    @Test
    @DisplayName("Should handle HccCacheEvict annotation")
    void testHccCacheEvictInterception() throws Throwable {
        // Given
        TestService service = new TestService();
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(interceptor);
        TestService proxy = (TestService) proxyFactory.getProxy();

        when(cacheEvictHandler.startInvalidate(any(), any()))
                .thenAnswer(invocation -> {
                    Runnable action = invocation.getArgument(1);
                    action.run();
                    return "success";
                });

        doNothing().when(cacheEvictHandler).addToSuccess(any());

        // When
        Object result = proxy.deleteData(789L);

        // Then
        assertEquals("success", result);
        verify(cacheEvictHandler).startInvalidate(any(), any());
        verify(cacheEvictHandler).addToSuccess(any());
    }

    @Test
    @DisplayName("Should parse SpEL expressions for cache keys")
    void testSpELKeyParsing() throws Throwable {
        // Given
        TestService service = new TestService();
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(interceptor);
        TestService proxy = (TestService) proxyFactory.getProxy();

        CacheValue<String> cachedValue = CacheValue.<String>builder()
                .value("user-123")
                .expireTime(System.currentTimeMillis() + 60000)
                .build();

        when(cacheExecutor.get(any(CacheKey.class), any())).thenReturn(cachedValue);

        // When
        Object result = proxy.getUserById(123L);

        // Then
        assertNotNull(result);
        verify(cacheExecutor).get(argThat(key -> 
            key.getKey().toString().contains("123")), any());
    }

    @Test
    @DisplayName("Should handle multiple parameters in SpEL")
    void testMultipleParametersSpEL() throws Throwable {
        // Given
        TestService service = new TestService();
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(interceptor);
        TestService proxy = (TestService) proxyFactory.getProxy();

        CacheValue<String> cachedValue = CacheValue.<String>builder()
                .value("composite-key-result")
                .expireTime(System.currentTimeMillis() + 60000)
                .build();

        when(cacheExecutor.get(any(CacheKey.class), any())).thenReturn(cachedValue);

        // When
        Object result = proxy.getCompositeData("type1", 999L);

        // Then
        assertNotNull(result);
        verify(cacheExecutor).get(any(CacheKey.class), any());
    }

    @Test
    @DisplayName("Should delegate to Spring cache for non-HccCache annotations")
    void testNonHccCacheDelegation() throws Throwable {
        // Given
        TestService service = new TestService();
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(interceptor);
        TestService proxy = (TestService) proxyFactory.getProxy();

        // When - Call method without HccCache annotations
        Object result = proxy.getWithoutAnnotation("test");

        // Then - Should execute normally
        assertEquals("test-result", result);
    }

    /**
     * Test service with various cache annotations
     */
    static class TestService {
        
        @HccCacheable(key = "#id", expireTime = 300)
        public String getDataWithCache(Long id) {
            return "actual-data-" + id;
        }

        @HccCacheEvict(key = "#id")
        public String deleteData(Long id) {
            return "deleted-" + id;
        }

        @HccCacheable(key = "#userId", expireTime = 600)
        public String getUserById(Long userId) {
            return "user-" + userId;
        }

        @HccCacheable(key = "#type + '-' + #id", expireTime = 300)
        public String getCompositeData(String type, Long id) {
            return type + "-data-" + id;
        }

        public String getWithoutAnnotation(String param) {
            return param + "-result";
        }
    }
}
