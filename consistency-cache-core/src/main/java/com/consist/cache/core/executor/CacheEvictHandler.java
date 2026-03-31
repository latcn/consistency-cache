package com.consist.cache.core.executor;

import com.consist.cache.core.function.CallableWithThrowable;
import com.consist.cache.core.model.InvalidationRecord;

/**
 * 数据库 数据变更，为保证强一致性，
 * 保证业务数据变更和记录缓存失效两个操作同时成功或失败
 *   a. 先新增缓存失效记录 状态为处理中
 *   transactionTemplate: b/c 放在同一事务中
 *   b. 更新缓存失效记录 状态为数据变更成功
 *   c. 再执行业务数据变更方法
 *   d. b/c执行失败，删除缓存失效记录
 *
 *   几种异常情况：主要针对不同数据库的情况。
 *      1. a 执行失败，直接回滚，数据一致
 *      2. a 执行成功，b/c执行失败
 *         1). b/c提交时宕机，b执行失败、c成功。后续扫描，可保证不漏删除缓存
 *         2). b/c提交时宕机，b执行成功，c失败。后续扫描，多删除缓存，异常情况
 *      3. b/c执行失败，直接删除缓存记录
 *         1). 删除失败（如宕机等），对于数据未变更的清除缓存
 *         2). 删除成功，数据一致
 *
 *         执行d,d执行成功，不用删除缓存；d执行失败，后续扫描，缓存多删除一次
 *
 *
 *
 *
 *  几种异常情况：
 *    1. a失败，直接回滚，数据一致
 *    2. a prepare成功，b失败，数据回滚，数据一致
 *    3. a、b prepare成功，但a/b不同数据库，事务提交时宕机，出现a/b不一致
 *        a成功，b失败 或 a失败，b成功
 *       针对a成功，b失败，后续扫描删除缓存
 *       针对a失败，b成功，无法处理
 *
 *   几种异常情况：
 *      1. 两个操作数据库已成功，但当前节点宕机，未能成功删除缓存
 *      2. 缓存已成功删除，但失效记录未能成功删除
 *      3. 业务数据变更成功但缓存记录未保存
 */
public interface CacheEvictHandler {

    /**
     * 业务数据变更前添加失效记录
     * @param invalidationRecord
     */
    Object startInvalidate(InvalidationRecord invalidationRecord, CallableWithThrowable<Object> targetCallback);

    /**
     * 删除缓存成功，添加到处理成功，后台批量删除已处理成功的
     * @param invalidationRecord
     */
    void addToSuccess(InvalidationRecord invalidationRecord);


}
