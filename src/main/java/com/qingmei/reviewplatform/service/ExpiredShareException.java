package com.qingmei.reviewplatform.service;

public class ExpiredShareException extends RuntimeException {
    public ExpiredShareException(String message) {
        super(message);
    }
}
