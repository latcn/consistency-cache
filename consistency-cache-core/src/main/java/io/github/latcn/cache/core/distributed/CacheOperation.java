package io.github.latcn.cache.core.distributed;

import io.github.latcn.cache.core.model.CacheKey;
import io.github.latcn.cache.core.model.CacheValue;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheOperation<V> {

	private CacheOperationType operationType;

	private CacheKey key;

	private CacheValue cacheValue;

	private ConcurrentLinkedQueue<CompletableFuture<V>> results;

	private long createTime;

	@Override
	public boolean equals(Object other) {
		if (other == null || !(other instanceof CacheOperation)) {
			return false;
		}
		CacheOperation otherOp = (CacheOperation) other;
		if (Objects.equals(this.operationType, otherOp.operationType) && Objects.equals(this.key, otherOp.key)
				&& Objects.equals(this.cacheValue, otherOp.cacheValue)) {
			return true;
		}
		return false;
	}

	@Override
	public int hashCode() {
		return Objects.hash(operationType, key, cacheValue);
	}

}
