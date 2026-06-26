package io.github.latcn.cache.core.local;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LocalCacheStats {

	private long hitCount;

	private long missCount;

	private double hitRate;

	private long size;

	private long maxSize;

	private long evictionCount;

	public String getFormattedHitRate() {
		return String.format("%.2f", this.hitRate * 100) + "%";
	}

}