package com.consist.cache.core.exception;

import lombok.Data;

@Data
public class CacheException extends RuntimeException {
    private Integer errorCode;
    private String errorMessage;

    public CacheException(String errorMessage) {
        super(errorMessage);
    }

    public CacheException(Integer errorCode, String errorMessage) {
        super(errorMessage);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public CacheException(Integer errorCode, String errorMessage, Throwable cause) {
        super(errorMessage, cause);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public static CacheException newInstance(CacheError cacheError) {
        return new CacheException(cacheError.getErrorCode(), cacheError.getErrorMessage());
    }
}
