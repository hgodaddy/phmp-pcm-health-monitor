package com.poynt.phmp.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ExecutionSummary {

    private final String region;
    private final String deviceSerial;
    private final String mode;
    private final Instant startedAt;
    private Instant finishedAt;
    private final List<ValidationResult> results = new ArrayList<>();
    private String buildId = "local-poc";
    private String logPath;
    private String reportPath;
    private boolean releaseReady;

    public ExecutionSummary(String region, String deviceSerial, String mode) {
        this.region = region;
        this.deviceSerial = deviceSerial;
        this.mode = mode;
        this.startedAt = Instant.now();
    }

    public void add(ValidationResult result) {
        results.add(result);
    }

    public void complete() {
        this.finishedAt = Instant.now();
    }

    public boolean isPassed() {
        return results.stream().allMatch(ValidationResult::isPassed);
    }

    public Duration duration() {
        Instant end = finishedAt == null ? Instant.now() : finishedAt;
        return Duration.between(startedAt, end);
    }

    public long passCount() {
        return results.stream().filter(ValidationResult::isPassed).count();
    }

    public long failCount() {
        return results.stream().filter(r -> !r.isPassed()).count();
    }

    /** Checks that could not be evaluated. Not failures, but not evidence either. */
    public long skipCount() {
        return results.stream().filter(ValidationResult::isSkipped).count();
    }

    public String getRegion() {
        return region;
    }

    public String getDeviceSerial() {
        return deviceSerial;
    }

    public String getMode() {
        return mode;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public List<ValidationResult> getResults() {
        return List.copyOf(results);
    }

    public String getBuildId() {
        return buildId;
    }

    public void setBuildId(String buildId) {
        this.buildId = buildId;
    }

    public String getLogPath() {
        return logPath;
    }

    public void setLogPath(String logPath) {
        this.logPath = logPath;
    }

    public String getReportPath() {
        return reportPath;
    }

    public void setReportPath(String reportPath) {
        this.reportPath = reportPath;
    }

    public boolean isReleaseReady() {
        return releaseReady;
    }

    public void setReleaseReady(boolean releaseReady) {
        this.releaseReady = releaseReady;
    }
}
