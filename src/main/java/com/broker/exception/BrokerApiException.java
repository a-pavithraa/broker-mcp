package com.broker.exception;

public class BrokerApiException extends RuntimeException {

    private final int statusCode;
    private final String errorCode;

    public BrokerApiException(String message) {
        super(message);
        this.statusCode = 0;
        this.errorCode = null;
    }

    public BrokerApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = null;
    }

    public BrokerApiException(String message, int statusCode, String errorCode) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public BrokerApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.errorCode = null;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
