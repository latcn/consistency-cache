package com.consist.cache.core.pubsub;

import com.consist.cache.core.model.NodeInstanceHolder;
import lombok.Data;

import java.util.HashSet;
import java.util.Set;

/**
 * Invalidation message for Pub/Sub broadcast.
 * 
 * Contains:
 * - Unique message ID for deduplication
 * - Source node ID for tracing
 * - Set of keys to invalidate
 */
@Data
public class InvalidationMessage extends BroadcastMessage {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * Cache keys to invalidate.
     */
    private Set<Object> keys = new HashSet<>();

    public InvalidationMessage() {
        this.messageId = generateMessageId();
        this.nodeId = NodeInstanceHolder.getNodeId();
    }
    
    /**
     * Create message with pre-populated keys.
     */
    public InvalidationMessage(String messageId, String nodeId, Set<Object> keys) {
        this.messageId = messageId;
        this.nodeId = nodeId;
        this.keys = keys != null ? keys : new HashSet<>();
    }
    
    /**
     * Add a key to the invalidation set.
     */
    public void addKey(Object key) {
        this.keys.add(key);
    }
    
    /**
     * Get number of keys in this batch.
     */
    public int getKeyCount() {
        return keys.size();
    }
    
    @Override
    public String toString() {
        return String.format(
            "InvalidationMessage{id=%s, node=%s, keys=%d}",
            messageId, nodeId, keys.size()
        );
    }
}
