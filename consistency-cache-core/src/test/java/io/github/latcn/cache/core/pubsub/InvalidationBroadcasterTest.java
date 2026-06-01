package io.github.latcn.cache.core.pubsub;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("InvalidationBroadcaster Tests")
class InvalidationBroadcasterTest {

    @Mock
    private BroadcastPublisher publisher;

    @Mock
    private BroadcastSubscriber<Object, BroadcasterListener> subscriber;

    private AutoCloseable mocks;
    private InvalidationBroadcaster<Object, BroadcasterListener> broadcaster;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (broadcaster != null) {
            broadcaster.preDestroy();
        }
        mocks.close();
    }

    @Test
    @DisplayName("Should add key to pending set")
    void testAddKey() throws Exception {
        Set<String> channels = Set.of("channel-1");
        broadcaster = new InvalidationBroadcaster<>(
            publisher, subscriber, new ArrayList<>(), channels, 10, 5
        );

        broadcaster.addKey("test-key");
        broadcaster.publish();

        verify(publisher).broadcastMessage(eq(channels), any(InvalidationMessage.class));
    }

    @Test
    @DisplayName("Should ignore null keys")
    void testAddNullKey() {
        Set<String> channels = Set.of("channel-1");
        broadcaster = new InvalidationBroadcaster<>(
            publisher, subscriber, new ArrayList<>(), channels, 10, 5
        );

        broadcaster.addKey(null);
        broadcaster.publish();

        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("Should publish when batch size is reached")
    void testBatchPublishing() throws Exception {
        int batchSize = 3;
        Set<String> channels = Set.of("channel-1");
        broadcaster = new InvalidationBroadcaster<>(
            publisher, subscriber, new ArrayList<>(), channels, batchSize, 5
        );

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(publisher).broadcastMessage(any(Set.class), any(InvalidationMessage.class));

        broadcaster.addKey("key-1");
        broadcaster.addKey("key-2");
        broadcaster.addKey("key-3");

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Batch publish should have been triggered");
        verify(publisher).broadcastMessage(eq(channels), any(InvalidationMessage.class));
    }

    @Test
    @DisplayName("Should create InvalidationMessage with correct structure")
    void testInvalidationMessageStructure() {
        Set<Object> keys = new HashSet<>(Arrays.asList("key-1", "key-2"));
        
        InvalidationMessage message = new InvalidationMessage();
        message.setKeys(keys);

        assertNotNull(message.getMessageId());
        assertEquals(keys, message.getKeys());
        assertNotNull(message.getNodeId());
    }

    @Test
    @DisplayName("Should handle retry logic")
    void testRetryLogic() throws Exception {
        Set<String> channels = Set.of("channel-1");
        broadcaster = new InvalidationBroadcaster<>(
            publisher, subscriber, new ArrayList<>(), channels, 10, 5
        );

        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        
        doAnswer(invocation -> {
            int attempt = attemptCount.incrementAndGet();
            if (attempt == 1) {
                throw new RuntimeException("Network error");
            }
            latch.countDown();
            return null;
        }).when(publisher).broadcastMessage(any(Set.class), any(InvalidationMessage.class));

        broadcaster.addKey("retry-key");
        broadcaster.publish();

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Retry should succeed");
        assertEquals(2, attemptCount.get(), "Should have retried once");
    }

    @Test
    @DisplayName("Should stop retrying after max retries")
    void testMaxRetries() throws Exception {
        Set<String> channels = Set.of("channel-1");
        broadcaster = new InvalidationBroadcaster<>(
            publisher, subscriber, new ArrayList<>(), channels, 10, 5
        );

        doThrow(new RuntimeException("Persistent error"))
            .when(publisher).broadcastMessage(any(Set.class), any(InvalidationMessage.class));

        broadcaster.addKey("failing-key");
        broadcaster.publish();

        Thread.sleep(5000);

        verify(publisher, org.mockito.Mockito.times(4)).broadcastMessage(any(Set.class), any(InvalidationMessage.class));
    }

    @Test
    @DisplayName("Should use exponential backoff for retries")
    void testExponentialBackoff() throws Exception {
        Set<String> channels = Set.of("channel-1");
        broadcaster = new InvalidationBroadcaster<>(
            publisher, subscriber, new ArrayList<>(), channels, 10, 5
        );

        AtomicLong firstAttemptTime = new AtomicLong(0);
        AtomicLong secondAttemptTime = new AtomicLong(0);
        AtomicInteger attemptCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        doAnswer(invocation -> {
            int attempt = attemptCount.incrementAndGet();
            long currentTime = System.currentTimeMillis();
            
            if (attempt == 1) {
                firstAttemptTime.set(currentTime);
                throw new RuntimeException("First failure");
            } else {
                secondAttemptTime.set(currentTime);
                latch.countDown();
            }
            return null;
        }).when(publisher).broadcastMessage(any(Set.class), any(InvalidationMessage.class));

        broadcaster.addKey("backoff-test");
        broadcaster.publish();

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Retry should complete");
        
        long delay = secondAttemptTime.get() - firstAttemptTime.get();
        assertTrue(delay >= 900, "Exponential backoff delay should be at least 1 second");
    }

    @Test
    @DisplayName("Should handle empty key set gracefully")
    void testEmptyKeySet() {
        Set<String> channels = Set.of("channel-1");
        broadcaster = new InvalidationBroadcaster<>(
            publisher, subscriber, new ArrayList<>(), channels, 10, 5
        );

        broadcaster.publish();

        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("Should support multiple channels")
    void testMultipleChannels() throws Exception {
        Set<String> channels = Set.of("channel-1", "channel-2", "channel-3");
        broadcaster = new InvalidationBroadcaster<>(
            publisher, subscriber, new ArrayList<>(), channels, 10, 5
        );

        doNothing().when(publisher).broadcastMessage(any(Set.class), any(InvalidationMessage.class));

        broadcaster.addKey("multi-channel-key");
        broadcaster.publish();

        verify(publisher).broadcastMessage(eq(channels), any(InvalidationMessage.class));
    }

    @Test
    @DisplayName("Should handle concurrent publish calls")
    void testConcurrentPublish() throws Exception {
        Set<String> channels = Set.of("channel-1");
        broadcaster = new InvalidationBroadcaster<>(
            publisher, subscriber, new ArrayList<>(), channels, 10, 5
        );

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<InvalidationMessage> capturedMessage = new AtomicReference<>();
        
        doAnswer(invocation -> {
            capturedMessage.set((InvalidationMessage) invocation.getArgument(1));
            latch.countDown();
            return null;
        }).when(publisher).broadcastMessage(any(Set.class), any(InvalidationMessage.class));

        broadcaster.addKey("key-1");
        broadcaster.addKey("key-2");

        Thread thread1 = new Thread(broadcaster::publish);
        Thread thread2 = new Thread(broadcaster::publish);
        
        thread1.start();
        thread2.start();
        
        thread1.join();
        thread2.join();

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Publish should complete");
        
        assertNotNull(capturedMessage.get());
        assertEquals(2, capturedMessage.get().getKeys().size());
    }

    @Test
    @DisplayName("Should publish correct keys")
    void testPublishCorrectKeys() throws Exception {
        Set<String> channels = Set.of("channel-1");
        broadcaster = new InvalidationBroadcaster<>(
            publisher, subscriber, new ArrayList<>(), channels, 10, 5
        );

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<InvalidationMessage> capturedMessage = new AtomicReference<>();
        
        doAnswer(invocation -> {
            capturedMessage.set((InvalidationMessage) invocation.getArgument(1));
            latch.countDown();
            return null;
        }).when(publisher).broadcastMessage(any(Set.class), any(InvalidationMessage.class));

        broadcaster.addKey("key-a");
        broadcaster.addKey("key-b");
        broadcaster.addKey("key-c");
        broadcaster.publish();

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Publish should complete");
        
        assertNotNull(capturedMessage.get());
        Set<Object> keys = capturedMessage.get().getKeys();
        assertEquals(3, keys.size());
        assertTrue(keys.contains("key-a"));
        assertTrue(keys.contains("key-b"));
        assertTrue(keys.contains("key-c"));
    }
}