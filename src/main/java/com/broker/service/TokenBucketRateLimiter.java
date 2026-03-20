package com.broker.service;

import com.broker.exception.BreezeApiException;

final class TokenBucketRateLimiter implements ZerodhaGatewayService.RateLimiter {

    private final double refillPerSecond;
    private final double capacity;
    private final String interruptMessage;
    private double tokens;
    private long lastRefillAtNanos;

    TokenBucketRateLimiter(double permitsPerSecond, double capacity, String interruptMessage) {
        this.refillPerSecond = permitsPerSecond;
        this.capacity = capacity;
        this.interruptMessage = interruptMessage;
        this.tokens = capacity;
        this.lastRefillAtNanos = System.nanoTime();
    }

    @Override
    public synchronized void acquire() {
        while (true) {
            refill();
            if (tokens >= 1d) {
                tokens -= 1d;
                return;
            }
            long sleepMillis = Math.max(10L, (long) Math.ceil(((1d - tokens) / refillPerSecond) * 1000));
            try {
                wait(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BreezeApiException(interruptMessage, e);
            }
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillAtNanos;
        if (elapsedNanos <= 0) {
            return;
        }
        lastRefillAtNanos = now;
        tokens = Math.min(capacity, tokens + (elapsedNanos / 1_000_000_000d) * refillPerSecond);
        notifyAll();
    }
}
