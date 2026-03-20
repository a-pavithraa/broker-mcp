package com.broker.exception;

public class SessionNotInitializedException extends RuntimeException {

    public SessionNotInitializedException() {
        super("No broker session initialized. Please use breeze_set_session (for ICICI) or zerodha_set_session (for Zerodha) first.");
    }

    public SessionNotInitializedException(String message) {
        super(message);
    }
}
