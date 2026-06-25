package io.github.latcn.cache.core.exception;

public enum CacheError {

	// ========== 参数验证异常 (100001-100099) ==========
	EMPTY_KEY(100001, "Cache key cannot be null or empty"), ERROR_KEY_TYPE(100002, "Key type is not supported"),
	INVALID_CACHE_LEVEL(100003, "Invalid cache level specified"),
	INVALID_CONSISTENCY_LEVEL(100004, "Invalid consistency level specified"),
	INVALID_PARAMETER(100005, "Invalid parameter provided"),
	EMPTY_BROADCASTER_TOPIC(100006, "BroadcasterListener topic cannot be empty"),

	// ========== 缓存操作异常 (200001-200999) ==========
	CACHE_GET_FAILED(200001, "Failed to get value from cache"),
	CACHE_PUT_FAILED(200002, "Failed to put value into cache"),
	CACHE_EVICT_FAILED(200003, "Failed to evict cache entry"), CACHE_NOT_EXISTS(200004, "Cache entry does not exist"),
	CACHE_VALUE_EXPIRED(200005, "Cache value has expired"),

	// ========== 分布式缓存异常 (201001-201999) ==========
	REDIS_CONNECTION_FAILED(201001, "Failed to connect to Redis"),
	REDIS_OPERATION_FAILED(201002, "Redis operation failed"), REDIS_TIMEOUT(201003, "Redis operation timeout"),
	REDIS_SCRIPT_NOT_FOUND(201004, "Redis script not found"),
	REDIS_BATCH_EXECUTION_FAILED(201005, "Redis batch execution failed"),

	// ========== 数据库操作异常 (300001-300999) ==========
	DB_INSERT_FAILED(300001, "Failed to insert database record"),
	DB_UPDATE_FAILED(300002, "Failed to update database record"),
	DB_DELETE_FAILED(300003, "Failed to delete database record"),
	DB_QUERY_FAILED(300004, "Failed to query database record"),
	DB_DUPLICATE_KEY(300005, "Duplicate key constraint violation"),
	DB_CONNECTION_FAILED(300006, "Failed to get database connection"),

	// ========== 广播/订阅异常 (400001-400999) ==========
	BROADCAST_FAILED(400001, "Failed to broadcast message"), SUBSCRIBE_FAILED(400002, "Failed to subscribe to channel"),
	MESSAGE_PUBLISH_FAILED(400003, "Failed to publish message"),
	CHANNEL_NOT_FOUND(400004, "Broadcast channel not found"),

	// ========== 热点检测异常 (500001-500999) ==========
	HOT_KEY_DETECTION_FAILED(500001, "Hot key detection failed"), HOT_KEY_NULL_VALUE(500002, "Hot key value is null"),
	BLACKLIST_OPERATION_FAILED(500003, "Blacklist operation failed"),

	// ========== 熔断器异常 (600001-600999) ==========
	CIRCUIT_BREAKER_OPEN(600001, "Cache circuit breaker is OPEN"),
	CIRCUIT_BREAKER_ERROR(600002, "Circuit breaker operation error"),
	CIRCUIT_BREAKER_THRESHOLD(600003, "Circuit breaker threshold exceeded"),

	// ========== 类加载/反射异常 (700001-700999) ==========
	CLASS_NOT_FOUND(700001, "Class not found"), CLASS_INSTANTIATION_FAILED(700002, "Failed to instantiate class"),
	CONSTRUCTOR_NOT_FOUND(700003, "Constructor not found"),
	REFLECTION_OPERATION_FAILED(700004, "Reflection operation failed"),

	// ========== 执行器异常 (800001-800999) ==========
	EXECUTOR_SHUTDOWN(800001, "Executor has been shutdown"), TASK_EXECUTION_FAILED(800002, "Task execution failed"),
	SINGLE_FLIGHT_TIMEOUT(800003, "SingleFlight execution timeout"),
	SINGLE_FLIGHT_INTERRUPTED(800004, "SingleFlight was interrupted"), EXECUTION_FAILED(800005, "Execution failed"),

	// ========== 本地缓存异常 (900001-900999) ==========
	LOCAL_CACHE_NOT_EXISTS(900001, "Local cache implementation not found"),
	LOCAL_CACHE_LOAD_FAILED(900002, "Local cache load failed"),
	LOCAL_CACHE_MARKER_FAILED(900003, "Local cache marker operation failed"),

	// ========== 配置/初始化异常 (1000001-1000999) ==========
	CONFIG_INVALID(1000001, "Invalid configuration"), BEAN_INITIALIZATION_FAILED(1000002, "Bean initialization failed"),
	PROPERTY_MISSING(1000003, "Required property is missing"),

	// ========== 不支持操作异常 (1100001-1100999) ==========
	UNSUPPORTED_OPERATION(1100001, "Unsupported operation"),

	// ========== 通用异常 (1200001-1200999) ==========
	NOT_EXISTS_LOCAL_CACHE_CLASS(1200001, "Local cache class not found"),;

	CacheError(Integer errorCode, String errorMessage) {
		this.errorCode = errorCode;
		this.errorMessage = errorMessage;
	}

	private final Integer errorCode;

	private final String errorMessage;

	public Integer getErrorCode() {
		return errorCode;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

}
