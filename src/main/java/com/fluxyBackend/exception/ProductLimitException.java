package com.fluxyBackend.exception;

public class ProductLimitException extends RuntimeException {
    private final int limit;
    private final int current;
    private final String plan;

    public ProductLimitException(int limit, int current, String plan) {
        super("Has alcanzado el límite de " + limit + " productos para el plan " + plan);
        this.limit = limit;
        this.current = current;
        this.plan = plan;
    }

    public int getLimit() { return limit; }
    public int getCurrent() { return current; }
    public String getPlan() { return plan; }
}
