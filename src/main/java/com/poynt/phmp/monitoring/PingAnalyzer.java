package com.poynt.phmp.monitoring;

import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.device.DeviceClient;
import com.poynt.phmp.logs.ScenarioLogAnalyzer;
import com.poynt.phmp.model.ValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PingAnalyzer {

    private final PhmpConfig config;
    private final DeviceClient device;

    public PingAnalyzer(PhmpConfig config, DeviceClient device) {
        this.config = config;
        this.device = device;
    }

    public ValidationResult analyze() {
        DeviceClient.PingStats stats = device.collectPingStats(config.phmp.timeouts.healthMonitorSeconds);
        List<String> details = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        details.add("Ping interval ms=" + stats.pingIntervalMs());
        details.add("Pong interval ms=" + stats.pongIntervalMs());
        details.add("Latency ms=" + stats.latencyMs());
        details.add("Missing heartbeats=" + stats.missingHeartbeats());

        if (stats.pingIntervalMs() != config.phmp.websocket.pingIntervalMs && config.isSimulate()) {
            failures.add("Ping interval mismatch");
        }
        if (stats.latencyMs() > config.phmp.websocket.maxLatencyMs) {
            failures.add("Latency too high: " + stats.latencyMs() + "ms > " + config.phmp.websocket.maxLatencyMs + "ms");
        }
        if (stats.missingHeartbeats() > 0) {
            failures.add("Missing heartbeats detected: " + stats.missingHeartbeats());
        }

        if (!config.isSimulate()) {
            ScenarioLogAnalyzer analyzer = device.logAnalyzer();
            long expectedMs = analyzer.detectedPingProfileMs(config.phmp.websocket.pingIntervalMs);
            List<Long> pingEvents = analyzer.pingEventTimes();
            long maxGapMs = analyzer.maxGapMs(pingEvents);
            long medianGapMs = analyzer.medianGapMs(pingEvents);
            details.add("Detected ping profile ms=" + expectedMs);
            details.add("Ping/pong sample count=" + pingEvents.size());
            details.add("Ping/pong max gap ms=" + maxGapMs);
            details.add("Ping/pong median gap ms=" + medianGapMs);

            if (pingEvents.size() < 2) {
                failures.add("Insufficient ping/pong events for cadence analysis");
            } else {
                if (maxGapMs > 2 * expectedMs) {
                    failures.add("Ping/pong cadence stopped or drifted: max gap " + maxGapMs + "ms > " + (2 * expectedMs) + "ms");
                }
                double min = expectedMs * 0.5;
                double max = expectedMs * 1.5;
                if (medianGapMs < min || medianGapMs > max) {
                    failures.add("Ping/pong cadence off expected profile. median=" + medianGapMs
                            + "ms expected~" + expectedMs + "ms");
                }
            }
        }

        if (!failures.isEmpty()) {
            return ValidationResult.fail("Ping/Pong Monitoring", String.join("; ", failures), failures);
        }
        return ValidationResult.pass(
                "Ping/Pong Monitoring",
                "Ping/pong cadence and latency within thresholds",
                details,
                Map.of(
                        "latencyMs", stats.latencyMs(),
                        "pingIntervalMs", stats.pingIntervalMs(),
                        "missingHeartbeats", stats.missingHeartbeats()
                )
        );
    }
}
