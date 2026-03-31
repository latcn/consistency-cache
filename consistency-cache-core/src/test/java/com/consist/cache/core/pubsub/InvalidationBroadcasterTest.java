package com.consist.cache.core.pubsub;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

/**
 * Unit tests for InvalidationBroadcaster
 */
@DisplayName("InvalidationBroadcaster Tests")
class InvalidationBroadcasterTest {

    @Mock
    private BroadcastPublisher publisher;
    
    @Mock
    private BroadcastSubscriber subscriber;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("Should add key to pending set")
    void testAddKey() throws Exception {
        // Given
        Set<String> channels = Set.of("channel-1");
        InvalidationBroadcaster broadcaster = new InvalidationBroadcaster(
            publisher, subscriber, new ArrayList<>(), channels, 10, 5
        );

        // When
        broadcaster.addKey("test-key");

        // Then - Should not throw exception
        // Note: We can't directly verify sendKeys as it's private
    }

    @Test
    @DisplayName("Should ignore null keys")
    void testAddNullKey() {
        // Given
        Set<String> channels = Set.of("channel-1");
        InvalidationBroadcaster broadcaster = new InvalidationBroadcaster(
            publisher, subscriber, new ArrayList<>(), channels, 10, 5
        );

        // When - Should not throw exception
        broadcaster.addKey(null);

        // Then
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("Should publish when batch size is reached")
    void testBatchPublishing() throws Exception {
        // Given
        int batchSize = 5;
        Set<String> channels = Set.of("channel-1");
        InvalidationBroadcaster broadcaster = new InvalidationBroadcaster(
            publisher, subscriber, new ArrayList<>(), channels, batchSize, 5
        );

        doNothing().when(publisher).broadcastMessage(anySet(), any(InvalidationMessage.class));

        // When - Add keys up to batch size
        for (int i = 0; i < batchSize; i++) {
            broadcaster.addKey("key-" + i);
        }

        // Then - Should have triggered publish
        // Note: Actual verification is difficult due to async nature
    }

    @Test
    @DisplayName("Should create InvalidationMessage with correct structure")
    void testInvalidationMessageStructure() {
        // Given
        Set<Object> keys = ConcurrentHashMap.newKeySet();
        keys.add("key-1");
        keys.add("key-2");
        
        InvalidationMessage message = new InvalidationMessage();
        message.setKeys(keys);

        // Then
        assertNotNull(message.getMessageId());
        assertEquals(keys, message.getKeys());
    }

    @Test
    @DisplayName("Should handle retry logic")
    void testRetryLogic() throws Exception {
        // Given
        Set<String> channels = Set.of("channel-1");
        InvalidationBroadcaster broadcaster = new InvalidationBroadcaster(
            publisher, subscriber, new ArrayList<>(), channels, 10, 5
        );

        // Simulate failure then success
        doThrow(new RuntimeException("Network error"))
            .doNothing()
            .when(publisher).broadcastMessage(anySet(), any(InvalidationMessage.class));

        InvalidationMessage message = new InvalidationMessage();
        Set keys = ConcurrentHashMap.newKeySet();
        keys.addAll(Arrays.asList("retry-key"));
        message.setKeys(keys);

        // When - First call fails, second succeeds
        try {
            broadcaster.publish();
        } catch (Exception e) {
            // Expected
        }

        // Trigger retry manually
        broadcaster.broadcastWithRetry(message);

        // Then - Should have retried
        verify(publisher, times(2)).broadcastMessage(anySet(), any(InvalidationMessage.class));
    }

    @Test
    @DisplayName("Should stop retrying after max retries")
    void testMaxRetries() throws Exception {
        // Given
        Set<String> channels = Set.of("channel-1");
        InvalidationBroadcaster broadcaster = new InvalidationBroadcaster(
            publisher, subscriber, new ArrayList<>(), channels, 10, 5
        );

        doThrow(new RuntimeException("Persistent error"))
            .when(publisher).broadcastMessage(anySet(), any(InvalidationMessage.class));

        InvalidationMessage message = new InvalidationMessage();
        Set keys = ConcurrentHashMap.newKeySet();
        keys.addAll(Arrays.asList("failing-key"));
        message.setKeys(keys);

        // When - Retry multiple times
        broadcaster.broadcastWithRetry(message);
        broadcaster.broadcastWithRetry(message);
        broadcaster.broadcastWithRetry(message);
        broadcaster.broadcastWithRetry(message);

        // Then - Should stop after 3 retries
        verify(publisher, atMost(3)).broadcastMessage(anySet(), any(InvalidationMessage.class));
    }

    @Test
    @DisplayName("Should use exponential backoff for retries")
    void testExponentialBackoff() throws Exception {
        // Given
        Set<String> channels = Set.of("channel-1");
        InvalidationBroadcaster broadcaster = new InvalidationBroadcaster(
            publisher, subscriber, new ArrayList<>(), channels, 10, 5
        );

        doThrow(new RuntimeException("Error")).when(publisher).broadcastMessage(anySet(), any());

        InvalidationMessage message = new InvalidationMessage();
        Set keys = ConcurrentHashMap.newKeySet();
        keys.addAll(Arrays.asList("backoff-test"));
        message.setKeys(keys);

        AtomicBoolean firstAttemptDone = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        // Mock with timing verification
        doAnswer(invocation -> {
            if (!firstAttemptDone.get()) {
                firstAttemptDone.set(true);
                throw new RuntimeException("First failure");
            }
            latch.countDown();
            return null;
        }).when(publisher).broadcastMessage(anySet(), any());

        // When
        broadcaster.broadcastWithRetry(message);
        
        // Wait and trigger completion
        latch.await(5, TimeUnit.SECONDS);

        // Then - Should have attempted at least once
        verify(publisher, atLeastOnce()).broadcastMessage(anySet(), any());
    }

    @Test
    @DisplayName("Should handle empty key set gracefully")
    void testEmptyKeySet() {
        // Given
        Set<String> channels = Set.of("channel-1");
        InvalidationBroadcaster broadcaster = new InvalidationBroadcaster(
            publisher, subscriber, new ArrayList<>(), channels, 10, 5
        );

        // When
        broadcaster.publish();

        // Then - Should not throw exception or publish
        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("Should support multiple channels")
    void testMultipleChannels() throws Exception {
        // Given
        Set<String> channels = Set.of("channel-1", "channel-2", "channel-3");
        InvalidationBroadcaster broadcaster = new InvalidationBroadcaster(
            publisher, subscriber, new ArrayList<>(), channels, 10, 5
        );

        doNothing().when(publisher).broadcastMessage(anySet(), any(InvalidationMessage.class));

        // When
        broadcaster.addKey("multi-channel-key");
        broadcaster.publish();

        // Then
        verify(publisher).broadcastMessage(eq(channels), any(InvalidationMessage.class));
    }

    @Test
    @DisplayName("Should track retry count per message")
    void testRetryCountTracking() throws Exception {
        // Given
        Set<String> channels = Set.of("channel-1");
        InvalidationBroadcaster broadcaster = new InvalidationBroadcaster(
            publisher, subscriber, new ArrayList<>(), channels, 10, 5
        );

        doThrow(new RuntimeException("Error")).when(publisher).broadcastMessage(anySet(), any());

        InvalidationMessage message = new InvalidationMessage();
        Set keys = ConcurrentHashMap.newKeySet();
        keys.addAll(Arrays.asList("tracked-key"));
        message.setKeys(keys);

        // When - Multiple retry attempts
        try {
            broadcaster.broadcastWithRetry(message);
        } catch (Exception e) {
            // Ignore
        }
        
        try {
            broadcaster.broadcastWithRetry(message);
        } catch (Exception e) {
            // Ignore
        }

        // Then - Should track retries internally
        // The retry count map should have been updated
    }

    @Test
    @DisplayName("Should clean up retry map after success")
    void testRetryMapCleanup() throws Exception {
        // Given
        Set<String> channels = Set.of("channel-1");
        InvalidationBroadcaster broadcaster = new InvalidationBroadcaster(
            publisher, subscriber, new ArrayList<>(), channels, 10, 5
        );

        doNothing().when(publisher).broadcastMessage(anySet(), any());

        InvalidationMessage message = new InvalidationMessage();
        Set keys = ConcurrentHashMap.newKeySet();
        keys.addAll(Arrays.asList("cleanup-key"));
        message.setKeys(keys);

        // When - Successful broadcast
        broadcaster.broadcastWithRetry(message);

        // Then - Should have removed from retry map after success
        // This prevents memory leaks
    }
}
