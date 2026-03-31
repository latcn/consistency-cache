package com.consist.cache.core.model;

import com.consist.cache.core.util.StringUtil;

import java.util.Enumeration;

/**
 * 使用的缓存级别
 */
public enum CacheLevel {

    /**
     *  仅使用本地缓存
     *  适用场景：针对缓存的数据更改频率低 比如系统字典表等信息，需要db存储但变动不频繁
     *   采用redis pub/sub通知失效，ttl兜底
     *   对于redis抖动的情况，根据容忍度，选择清空或依据ttl自动过期
     *   针对消息广播爆炸的情况，禁用本地缓存、pub/sub
     */
    LOCAL_CACHE,

    /**
     * 本地缓存和二级缓存如redis混合使用
     * 适用场景：
     *    缓存更新频率不高，且可容忍短暂的不一致，偶尔read qps过高，读多写少
     *    优先采用二级缓存
     *       对于读热key，各节点各自统计热key, 缓存一份到本地，变更时采用redis pub/sub通知本地缓存失效
     *       对于写热key, 各节点通过pub/sub 的失效变更统计 写频率， 写热key 加入本地缓存黑名单，过段时间失效
     *  读取时：
     *     先读本地缓存，没有再读二级缓存，根据是否热key等因素决定是否缓存到本地
     *     二级缓存没有，则读取持久数据，然后更新到二级缓存中
     *  写入时：
     *     写入成功后，先失效本地缓存，再失效二级缓存，采用事务内部保证二级缓存的最终一致
     */
    ADAPTIVE_CACHE,

    /**
     * 仅使用二级缓存
     * 适用场景：
     *    一致性要求较高或请求频率不高的的情况
     *    读取：
     *      先读缓存，singleFlight模式 没有则从持久化数据中获取
     *    写入：
     *      先写入持久，再失效缓存 采用事务内部保证二级缓存的最终一致
     */
    L2_CACHE

    ;



}
