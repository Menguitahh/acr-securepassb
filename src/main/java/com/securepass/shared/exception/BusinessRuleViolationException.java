package com.securepass.shared.exception;

public class BusinessRuleViolationException extends RuntimeException {

    private final String rule;

    public BusinessRuleViolationException(String rule, String detail) {
        super(detail);
        if (rule == null || rule.isBlank()) throw new IllegalArgumentException("rule requerido");
        this.rule = rule;
    }

    public String rule() {
        return rule;
    }
}
