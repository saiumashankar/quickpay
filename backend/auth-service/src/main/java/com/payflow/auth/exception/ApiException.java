package com.payflow.auth.exception;

public class ApiException extends RuntimeException {
    public ApiException(String message) {
        super(message);
    }
}
