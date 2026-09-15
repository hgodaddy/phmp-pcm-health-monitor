package com.poynt.phmp.engine;

import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.model.ExecutionSummary;
import com.poynt.phmp.model.ValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Day 4 – turns technical validation results into a release-ready quality gate decision.
 */
public class ReleaseGateEvaluator {

    /** Phase 1 release-blocking checks (proposal readiness criteria). */
    private static final Set<String> CRITICAL = Set.of(
            "Flash / OTA Deployment Validation",
            "Installation Validation",
            "Startup Validation",
            "Authentication Validation",
            "WebSocket Validation",
            "Environment Validation",
            "Socket Health Monitoring",
            "Ping/Pong Monitoring",
            "Disconnect / Reconnect Validation",
            "Cloud Messaging Validation",
            "IoT Companion Gate"
    );

    private final PhmpConfig config;

    public ReleaseGateEvaluator(PhmpConfig config) {
        this.config = config;
    }

    public ValidationResult evaluate(ExecutionSummary summary) {
        List<String> details = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        List<ValidationResult> prior = summary.getResults();
        long criticalFails = prior.stream()
                .filter(r -> CRITICAL.contains(r.getName()))
                .filter(r -> !r.isPassed())
                .count();
        List<String> skippedCritical = prior.stream()
                .filter(r -> CRITICAL.contains(r.getName()) && r.isSkipped())
                .map(ValidationResult::getName)
                .toList();

        details.add("Total checks=" + prior.size());
        details.add("Passed=" + summary.passCount());
        details.add("Failed=" + summary.failCount());
        details.add("Critical failures=" + criticalFails);
        details.add("Critical skipped=" + skippedCritical.size());
        details.add("Region=" + summary.getRegion());
        details.add("BuildId=" + summary.getBuildId());
        details.add("Block on critical=" + config.phmp.releaseGate.blockOnAnyCriticalFailure);

        if (config.phmp.releaseGate.blockOnAnyCriticalFailure && criticalFails > 0) {
            String failedNames = prior.stream()
                    .filter(r -> CRITICAL.contains(r.getName()) && !r.isPassed())
                    .map(ValidationResult::getName)
                    .collect(Collectors.joining(", "));
            failures.add("Release blocked by critical failures: " + failedNames);
        }
        if (!summary.isPassed()) {
            failures.add("One or more Phase 1 / IoT validators failed");
        }

        summary.setReleaseReady(failures.isEmpty() && skippedCritical.isEmpty());

        if (!failures.isEmpty()) {
            return ValidationResult.fail(
                    "Release Gate Decision",
                    "Build is NOT release-ready for region " + summary.getRegion(),
                    failures
            );
        }
        if (!skippedCritical.isEmpty()) {
            // Nothing failed, but a critical check produced no evidence, so the build cannot be
            // certified. This is reported as unevaluated rather than as a failure.
            List<String> notes = new ArrayList<>(details);
            notes.add("Unevaluated critical checks: " + String.join(", ", skippedCritical));
            notes.add("Every check that ran passed; certification needs the skipped checks to run");
            return ValidationResult.skip(
                    "Release Gate Decision",
                    "Not certifiable for region " + summary.getRegion()
                            + ": " + skippedCritical.size() + " critical check(s) not evaluated",
                    notes
            );
        }
        return ValidationResult.pass(
                "Release Gate Decision",
                "Build is release-ready for region " + summary.getRegion(),
                details,
                Map.of("releaseReady", true, "criticalFailures", 0)
        );
    }
}
