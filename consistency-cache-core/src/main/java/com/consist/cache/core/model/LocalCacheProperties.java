package com.consist.cache.core.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class LocalCacheProperties {

    /**
     * 缓存实现类型：GUAVA, CAFFEINE, CUSTOM
     */
    private String cacheType;

    /**
     * 初始容量
     */
    private int initialCapacity = 100;

    /**
     * 最大容量
     */
    private long maximumSize = 1000;

    /**
     * 写入后过期时间（秒）
     */
    private long expireAfterWrite = 600;

    /**
     * 访问后过期时间（秒）
     */
    private long expireAfterAccess = 600;

    /**
     * 漂移
     */
    private long bufferTimeMs = 1000;

    /**
     * 缓存失效广播topic
     */
    private String channelNames = "hcc_cache_evict";

    private int batchSize = 100;

    private int maxWaitSeconds = 5;

    /**
     * 读热点检测
     */
    private double readHotKeyThreshold = 100.0;
    private int readWindowSizeMs = 1000;
    private int readBucketCount = 10;

    /**
     * 写热点检测
     */
    private int writeWindowSeconds = 5*60;
    private int writeInvalidationThreshold = 10;
    private long writeBaseBlacklistTtl = 10*1000;
    private double writeBackoffMultiplier = 2;
    private long writeMaxBlacklistTime = 100*1000;

    /**
     * 自定义本地缓存类
     */
    private String customCacheClz;
}
