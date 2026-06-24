package io.github.latcn.cache.core.handler;

import io.github.latcn.cache.core.model.CacheKey;
import java.util.Map;
import java.util.function.Function;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheContext {

	private CacheKey cacheKey;

	private Function doSingleFlightFun;

	private Map<String, Object> params;

}
