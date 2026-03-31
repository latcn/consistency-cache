package com.consist.cache.core.pubsub;

import java.util.Set;

public interface BroadcastPublisher {

    void broadcastMessage(Set<String> channelNames, BroadcastMessage message);
}
