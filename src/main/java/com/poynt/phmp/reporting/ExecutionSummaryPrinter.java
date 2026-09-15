package com.poynt.phmp.reporting;

import com.poynt.phmp.model.ExecutionSummary;
import com.poynt.phmp.model.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ExecutionSummaryPrinter {

    private static final Logger log = LoggerFactory.getLogger(ExecutionSummaryPrinter.class);

    private ExecutionSummaryPrinter() {
    }

    public static void print(ExecutionSummary summary) {
        log.info("========== PHMP EXECUTION SUMMARY [{}] ==========", summary.getRegion());
        log.info("Device={} Mode={} Build={} Status={}",
                summary.getDeviceSerial(),
                summary.getMode(),
                summary.getBuildId(),
                summary.isPassed() ? "PASS" : "FAIL");
        for (ValidationResult result : summary.getResults()) {
            log.info("  - {}", result);
        }
        log.info("Passed={} Failed={} Skipped={} ReleaseReady={}",
                summary.passCount() - summary.skipCount(),
                summary.failCount(),
                summary.skipCount(),
                summary.isReleaseReady());
        log.info("Duration={}s Report={}", summary.duration().toSeconds(), summary.getReportPath());
        log.info("=================================================");
    }
}
