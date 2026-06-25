package io.github.latcn.cache.core.exception;

public class CacheException extends RuntimeException {

	private final int errorCode;

	private final String errorMessage;

	private final String errorCategory;

	public static CacheException newInstance(CacheError cacheError) {
		return new CacheException(cacheError.getErrorCode(), cacheError.getErrorMessage(),
				categorizeError(cacheError.getErrorCode()));
	}

	public static CacheException wrap(Throwable throwable, CacheError defaultError) {
		if (throwable instanceof CacheException) {
			return (CacheException) throwable;
		}
		return new CacheException(defaultError, throwable.getMessage(), throwable);
	}

	public static CacheException wrapUnsupportedOperation(Throwable throwable) {
		if (throwable instanceof CacheException) {
			return (CacheException) throwable;
		}
		return new CacheException(CacheError.UNSUPPORTED_OPERATION, throwable.getMessage(), throwable);
	}

	public static CacheException wrapIllegalArgument(Throwable throwable) {
		if (throwable instanceof CacheException) {
			return (CacheException) throwable;
		}
		return new CacheException(CacheError.INVALID_PARAMETER, throwable.getMessage(), throwable);
	}

	public static CacheException wrapExecution(Throwable throwable) {
		if (throwable instanceof CacheException) {
			return (CacheException) throwable;
		}
		if (throwable instanceof IllegalArgumentException) {
			return wrapIllegalArgument(throwable);
		}
		if (throwable instanceof UnsupportedOperationException) {
			return wrapUnsupportedOperation(throwable);
		}
		return new CacheException(CacheError.EXECUTION_FAILED, throwable.getMessage(), throwable);
	}

	private static String categorizeError(int errorCode) {
		if (errorCode >= 100001 && errorCode <= 100999) {
			return "VALIDATION";
		}
		if (errorCode >= 200001 && errorCode <= 200999) {
			return "CACHE_OPERATION";
		}
		if (errorCode >= 201001 && errorCode <= 201999) {
			return "DISTRIBUTED_CACHE";
		}
		if (errorCode >= 300001 && errorCode <= 300999) {
			return "DATABASE";
		}
		if (errorCode >= 400001 && errorCode <= 400999) {
			return "PUBSUB";
		}
		if (errorCode >= 500001 && errorCode <= 500999) {
			return "HOTSPOT";
		}
		if (errorCode >= 600001 && errorCode <= 600999) {
			return "CIRCUIT_BREAKER";
		}
		if (errorCode >= 700001 && errorCode <= 700999) {
			return "REFLECTION";
		}
		if (errorCode >= 800001 && errorCode <= 800999) {
			return "EXECUTOR";
		}
		if (errorCode >= 900001 && errorCode <= 900999) {
			return "LOCAL_CACHE";
		}
		return "UNKNOWN";
	}

	private CacheException(int errorCode, String errorMessage, String errorCategory) {
		super(errorMessage);
		this.errorCode = errorCode;
		this.errorMessage = errorMessage;
		this.errorCategory = errorCategory;
	}

	public CacheException(CacheError cacheError) {
		this(cacheError.getErrorCode(), cacheError.getErrorMessage(), categorizeError(cacheError.getErrorCode()));
	}

	public CacheException(CacheError cacheError, Throwable cause) {
		super(cacheError.getErrorMessage(), cause);
		this.errorCode = cacheError.getErrorCode();
		this.errorMessage = cacheError.getErrorMessage();
		this.errorCategory = categorizeError(cacheError.getErrorCode());
	}

	public CacheException(CacheError cacheError, String customMessage) {
		super(String.format("%s: %s", cacheError.getErrorMessage(), customMessage));
		this.errorCode = cacheError.getErrorCode();
		this.errorMessage = String.format("%s: %s", cacheError.getErrorMessage(), customMessage);
		this.errorCategory = categorizeError(cacheError.getErrorCode());
	}

	public CacheException(CacheError cacheError, String customMessage, Throwable cause) {
		super(String.format("%s: %s", cacheError.getErrorMessage(), customMessage), cause);
		this.errorCode = cacheError.getErrorCode();
		this.errorMessage = String.format("%s: %s", cacheError.getErrorMessage(), customMessage);
		this.errorCategory = categorizeError(cacheError.getErrorCode());
	}

	public int getErrorCode() {
		return errorCode;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public String getErrorCategory() {
		return errorCategory;
	}

}
