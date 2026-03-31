package com.consist.cache.core.exception;

public enum CacheError {

    EMPTY_KEY(100001, "key can't be null"),
    ERROR_KEY_TYPE(100002, "key type is not supported "),
    EMPTY_BROADCASTER_TOPIC(100003, "BroadcasterListener topic can't be empty "),
    NOT_EXISTS_LOCAL_CACHE_CLASS(100004, "not exists local cache class"),

    ;

    CacheError(Integer errorCode, String errorMessage) {
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    private Integer errorCode;
    private String errorMessage;

    public Integer getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(Integer errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
