package com.consist.cache.core.pubsub;

import lombok.Data;

import java.io.Serializable;
import java.util.UUID;

@Data
public abstract class BroadcastMessage implements Serializable {

    /**
     * Unique message identifier (for deduplication).
     * Format: timestamp-uuid
     */
    protected String messageId;

    /**
     * Source node identifier (for debugging/tracing).
     * Format: hostname-pid
     */
    protected String nodeId;


    protected String generateMessageId() {
        return System.currentTimeMillis() + "-" + UUID.randomUUID();
    }

}
