package io.github.latcn.cache.core.executor;

import io.github.latcn.cache.core.function.CallableWithThrowable;
import io.github.latcn.cache.core.model.InvalidationRecord;

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
