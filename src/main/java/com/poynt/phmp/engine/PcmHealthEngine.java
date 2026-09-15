package com.poynt.phmp.engine;

import com.poynt.phmp.cloud.CloudMessagingValidator;
import com.poynt.phmp.iot.IotCompanionValidator;
import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.device.DeviceClient;
import com.poynt.phmp.device.DeviceClientFactory;
import com.poynt.phmp.model.ExecutionSummary;
import com.poynt.phmp.model.ValidationResult;
import com.poynt.phmp.monitoring.HealthMonitor;
import com.poynt.phmp.monitoring.PingAnalyzer;
import com.poynt.phmp.reporting.HistoryStore;
import com.poynt.phmp.reporting.HtmlReportGenerator;
import com.poynt.phmp.reporting.ReleaseGateDashboard;
import com.poynt.phmp.validation.authentication.AuthenticationValidator;
import com.poynt.phmp.validation.deployment.FlashOtaValidator;
import com.poynt.phmp.validation.environment.EnvironmentValidator;
import com.poynt.phmp.validation.installation.InstallationValidator;
import com.poynt.phmp.validation.reconnect.ReconnectValidator;
import com.poynt.phmp.validation.startup.StartupValidator;
import com.poynt.phmp.validation.websocket.WebsocketValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Orchestrates the Phase 1 PCM validation lifecycle for a single region.
 */
public class PcmHealthEngine {

    private static final Logger log = LoggerFactory.getLogger(PcmHealthEngine.class);

    /** Expected validation steps including release gate. */
    public static final int PHASE1_STEP_COUNT = 12;

    private final PhmpConfig config;

    public PcmHealthEngine(PhmpConfig config) {
        this.config = config;
    }

    public ExecutionSummary runRegion(String region) {
        log.info("Starting PHMP lifecycle for region={} mode={}", region, config.phmp.mode);
        ExecutionSummary summary = new ExecutionSummary(
                region,
                config.region(region).device().serial,
                config.phmp.mode);
        String buildId = System.getenv().getOrDefault("BUILD_ID",
                System.getProperty("phmp.buildId", "local-poc"));
        summary.setBuildId(buildId);

        try (DeviceClient device = DeviceClientFactory.create(config, region)) {
            device.beginRunWindow();
            runLifecycle(summary, device);
            if (config.phmp.logging.collectLogcat) {
                String logPath = device.collectLogs(config.phmp.logging.logDir);
                summary.setLogPath(logPath);
                log.info("Collected device logs at {}", logPath);
            }
        } catch (RuntimeException e) {
            // A device or transport fault must still leave evidence behind; a run that produces no
            // report at all is indistinguishable from a run that was never attempted.
            log.error("Region {} aborted by device fault: {}", region, e.getMessage(), e);
            summary.add(ValidationResult.fail(
                    "Device Availability",
                    "Run aborted before completion: " + e.getMessage(),
                    List.of("Device serial=" + summary.getDeviceSerial(),
                            "Mode=" + config.phmp.mode,
                            "Fault=" + e.getClass().getSimpleName() + ": " + e.getMessage())));
            summary.setReleaseReady(false);
        }
        return publish(summary, region);
    }

    private ExecutionSummary publish(ExecutionSummary summary, String region) {
        summary.complete();
        String reportPath = new HtmlReportGenerator(config).write(summary);
        summary.setReportPath(reportPath);
        new HistoryStore(config).append(summary);
        String dashboardPath = new ReleaseGateDashboard(config).update(summary);
        log.info("Region {} finished status={} releaseReady={} report={} dashboard={}",
                region,
                summary.isPassed() ? "PASS" : "FAIL",
                summary.isReleaseReady(),
                reportPath,
                dashboardPath);
        return summary;
    }

    private void runLifecycle(ExecutionSummary summary, DeviceClient device) {
        // Day 1 – foundation / deploy
        runStep(summary, new FlashOtaValidator(config, device)::validate);

        // Day 2 – core PCM validation
        runStep(summary, new InstallationValidator(config, device)::validate);
        runStep(summary, new StartupValidator(config, device)::validate);
        runStep(summary, new AuthenticationValidator(config, device)::validate);
        runStep(summary, new WebsocketValidator(config, device)::validate);
        runStep(summary, new EnvironmentValidator(config, device)::validate);

        // Day 3 – end-to-end communication
        runStep(summary, new HealthMonitor(config, device)::monitor);
        runStep(summary, new PingAnalyzer(config, device)::analyze);
        runStep(summary, new ReconnectValidator(config, device)::validate);
        runStep(summary, new CloudMessagingValidator(config, device)::validate);
        runStep(summary, new IotCompanionValidator(config, device)::validate);

        // Day 4 – release gate
        runStep(summary, () -> new ReleaseGateEvaluator(config).evaluate(summary));
    }

    private void runStep(ExecutionSummary summary, Step step) {
        ValidationResult result = step.execute();
        summary.add(result);
        if (result.isPassed()) {
            log.info("{}", result);
        } else {
            log.error("{}", result);
        }
    }

    @FunctionalInterface
    private interface Step {
        ValidationResult execute();
    }
}
