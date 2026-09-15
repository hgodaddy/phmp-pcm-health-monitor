package com.poynt.phmp.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ValidationResult {

    private final String name;
    private final boolean passed;
    private final boolean skipped;
    private final String message;
    private final Instant timestamp;
    private final List<String> details;
    private final Map<String, Object> metrics;

    private ValidationResult(String name, boolean passed, boolean skipped, String message,
                             List<String> details, Map<String, Object> metrics) {
        this.name = name;
        this.passed = passed;
        this.skipped = skipped;
        this.message = message;
        this.timestamp = Instant.now();
        this.details = Collections.unmodifiableList(new ArrayList<>(details));
        this.metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }

    public static ValidationResult pass(String name, String message) {
        return new ValidationResult(name, true, false, message, List.of(), Map.of());
    }

    public static ValidationResult pass(String name, String message, List<String> details, Map<String, Object> metrics) {
        return new ValidationResult(name, true, false, message, details, metrics);
    }

    public static ValidationResult fail(String name, String message) {
        return new ValidationResult(name, false, false, message, List.of(), Map.of());
    }

    public static ValidationResult fail(String name, String message, List<String> details) {
        return new ValidationResult(name, false, false, message, details, Map.of());
    }

    /**
     * A check that could not be evaluated, for example because the environment it needs is not
     * configured yet. Not counted as a failure, but never counted as evidence either.
     */
    public static ValidationResult skip(String name, String message, List<String> details) {
        return new ValidationResult(name, true, true, message, details, Map.of("skipped", true));
    }

    public String getName() {
        return name;
    }

    public boolean isPassed() {
        return passed;
    }

    public boolean isSkipped() {
        return skipped;
    }

    public String getMessage() {
        return message;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public List<String> getDetails() {
        return details;
    }

    public Map<String, Object> getMetrics() {
        return metrics;
    }

    @Override
    public String toString() {
        return status() + " [" + name + "] " + message;
    }

    public String status() {
        if (skipped) {
            return "SKIP";
        }
        return passed ? "PASS" : "FAIL";
    }
}
