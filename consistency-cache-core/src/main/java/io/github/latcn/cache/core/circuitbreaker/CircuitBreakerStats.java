package io.github.latcn.cache.core.circuitbreaker;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CircuitBreakerStats {

	private CircuitBreakerState state;

	private long failureCount;

	private long successCount;

	private long totalCalls;

	private long rejectedCalls;

	private double rejectionRate;

}