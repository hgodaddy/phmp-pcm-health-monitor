package com.poynt.phmp.monitoring;

import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.device.DeviceClient;
import com.poynt.phmp.logs.ScenarioLogAnalyzer;
import com.poynt.phmp.model.ValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HealthMonitor {

    private final PhmpConfig config;
    private final DeviceClient device;

    public HealthMonitor(PhmpConfig config, DeviceClient device) {
        this.config = config;
        this.device = device;
    }

    public ValidationResult monitor() {
        DeviceClient.HealthSnapshot snapshot = device.collectHealthSnapshot(config.phmp.timeouts.healthMonitorSeconds);
        List<String> details = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        details.add("Connected duration seconds=" + snapshot.connectedDurationSeconds());
        details.add("Disconnects=" + snapshot.disconnects());
        details.add("Reconnects=" + snapshot.reconnects());
        details.add("Handshake failures=" + snapshot.handshakeFailures());
        details.add("Auth failures=" + snapshot.authFailures());

        if (snapshot.disconnects() > 0) {
            failures.add("Unexpected disconnects observed: " + snapshot.disconnects());
        }
        if (snapshot.handshakeFailures() > 0) {
            failures.add("Handshake failures observed: " + snapshot.handshakeFailures());
        }
        if (snapshot.authFailures() > 0) {
            failures.add("Authentication failures observed: " + snapshot.authFailures());
        }

        if (!config.isSimulate()) {
            ScenarioLogAnalyzer analyzer = device.logAnalyzer();
            ScenarioLogAnalyzer.ConnectivityStats connectivity = analyzer.connectivityStats();
            long observedMinutes = connectivity.observedWindowMs() / 60_000L;
            long connectedMinutes = connectivity.connectedDurationMs() / 60_000L;

            details.add("Observed log window minutes=" + observedMinutes);
            details.add("Connected window minutes=" + connectedMinutes);
            details.add("Reconnect chain violation=" + connectivity.reconnectChainViolation());
            details.add("Dead socket >10m=" + connectivity.deadSocketOverTenMinutes());
            details.add("Connection error signals=" + connectivity.errorSignals());

            if (!connectivity.mostlyConnected()) {
                failures.add("Socket not connected for the vast majority of observed window");
            }
            if (connectivity.reconnectChainViolation()) {
                failures.add("Disconnect without schedule/reconnect/recovery chain within expected backoff");
            }
            if (connectivity.deadSocketOverTenMinutes()) {
                failures.add("Dead socket detected for more than 10 minutes after disconnect");
            }
            if (connectivity.errorSignals() > 0) {
                failures.add("Handshake/connect failure markers detected: " + connectivity.errorSignals());
            }
        }

        if (!failures.isEmpty()) {
            return ValidationResult.fail("Socket Health Monitoring", String.join("; ", failures), failures);
        }
        return ValidationResult.pass(
                "Socket Health Monitoring",
                "Socket remained healthy during monitor window",
                details,
                Map.of(
                        "connectedDurationSeconds", snapshot.connectedDurationSeconds(),
                        "disconnects", snapshot.disconnects(),
                        "reconnects", snapshot.reconnects()
                )
        );
    }
}
