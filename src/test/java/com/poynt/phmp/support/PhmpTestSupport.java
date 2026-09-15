package com.poynt.phmp.support;

import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.engine.PcmHealthEngine;
import com.poynt.phmp.model.ExecutionSummary;
import com.poynt.phmp.reporting.ExecutionSummaryPrinter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;

public final class PhmpTestSupport {

    private PhmpTestSupport() {
    }

    private static boolean coversRegion(PhmpConfig config, String region) {
        String configured = config.phmp.region;
        return configured == null
                || configured.isBlank()
                || "both".equalsIgnoreCase(configured)
                || configured.equalsIgnoreCase(region);
    }

    public static PhmpConfig loadConfig() {
        return PhmpConfig.load();
    }

    public static ExecutionSummary runAndAssert(String region) {
        PhmpConfig config = loadConfig();
        // phmp.region selects which regions this run covers. Honouring it here keeps a single-terminal
        // device run from dialling the other region's unset serial and reporting a failure for it.
        Assumptions.assumeTrue(coversRegion(config, region),
                () -> "Skipping region " + region + "; phmp.region=" + config.phmp.region);
        ExecutionSummary summary = new PcmHealthEngine(config).runRegion(region);
        ExecutionSummaryPrinter.print(summary);
        Assertions.assertTrue(summary.isPassed(),
                () -> "PHMP validation failed for region " + region + " report=" + summary.getReportPath());

        // A skipped critical check keeps the build off release-ready by design, so only demand
        // release-readiness when every check was actually evaluated.
        if (summary.skipCount() == 0) {
            Assertions.assertTrue(summary.isReleaseReady(),
                    () -> "All checks passed but release gate blocked for region " + region
                            + " report=" + summary.getReportPath());
        } else {
            Assertions.assertFalse(summary.isReleaseReady(),
                    () -> "Release gate must not certify a run with " + summary.skipCount()
                            + " unevaluated checks for region " + region);
        }
        return summary;
    }
}
