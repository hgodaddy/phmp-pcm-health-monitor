package com.poynt.phmp.validation.websocket;

import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.device.DeviceClient;
import com.poynt.phmp.logs.ScenarioLogAnalyzer;
import com.poynt.phmp.model.ValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class WebsocketValidator {

    private final PhmpConfig config;
    private final DeviceClient device;

    public WebsocketValidator(PhmpConfig config, DeviceClient device) {
        this.config = config;
        this.device = device;
    }

    public ValidationResult validate() {
        List<String> details = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        DeviceClient.WebsocketState state = waitForConnection(config.phmp.timeouts.websocketConnectSeconds);
        if (!state.initialized()) {
            failures.add("Socket initialization not observed");
        } else {
            details.add("Socket initialized");
        }

        if (!state.connected()) {
            failures.add("WebSocket connection not established within "
                    + config.phmp.timeouts.websocketConnectSeconds + "s");
        } else {
            details.add("WebSocket connected endpoint=" + state.endpoint());
        }

        String expected = config.region(device.region()).websocket().endpoint;
        String actual = device.selectedEndpoint();
        String expectedHost = ScenarioLogAnalyzer.hostOf(expected);
        String actualHost = ScenarioLogAnalyzer.hostOf(actual);
        if (actualHost == null || actualHost.isBlank() || !actualHost.equalsIgnoreCase(expectedHost)) {
            failures.add("Endpoint selection mismatch. expectedHost=" + expectedHost + " actualHost=" + actualHost);
        } else {
            details.add("Endpoint selection correct");
        }

        // An OPEN socket is only meaningful if it was authenticated. PCM can reach OPEN on its unsecure
        // fallback URL after failing to obtain a token, and reporting that as a healthy socket is the
        // single most misleading thing this gate could do.
        if (!config.isSimulate()) {
            ScenarioLogAnalyzer analyzer = device.logAnalyzer();
            if (analyzer.hasUnsecureFallback() || analyzer.hasTokenlessConnectAttempt()) {
                failures.add("Socket was established without authentication (unsecure fallback URL)");
            } else if (state.connected()) {
                details.add("Socket carries an authenticated stream URL");
            }
        }

        if (!failures.isEmpty()) {
            return ValidationResult.fail("WebSocket Validation", String.join("; ", failures), failures);
        }
        return ValidationResult.pass(
                "WebSocket Validation",
                "Socket init, connection, and endpoint validated",
                details,
                Map.of("endpoint", actual, "timeoutSeconds", config.phmp.timeouts.websocketConnectSeconds)
        );
    }

    private DeviceClient.WebsocketState waitForConnection(int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        DeviceClient.WebsocketState latest = device.getWebsocketState();
        while (System.currentTimeMillis() < deadline) {
            latest = device.getWebsocketState();
            if (latest.initialized() && latest.connected()) {
                return latest;
            }
            try {
                Thread.sleep(config.isSimulate() ? 50L : 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return latest;
    }
}
