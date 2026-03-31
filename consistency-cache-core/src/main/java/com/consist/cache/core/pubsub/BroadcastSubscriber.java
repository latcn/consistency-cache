package com.consist.cache.core.pubsub;

public interface BroadcastSubscriber<T, S extends BroadcasterListener> {

    T broadcastSubscribe(String channelName, S listener);

    void removeSubscribe(String channelName, T listenerId);
}
