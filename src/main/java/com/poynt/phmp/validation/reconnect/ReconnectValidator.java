package com.poynt.phmp.validation.reconnect;

import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.device.DeviceClient;
import com.poynt.phmp.model.ValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Day 3 – validates disconnect / reconnect recovery under a controlled scenario.
 */
public class ReconnectValidator {

    private final PhmpConfig config;
    private final DeviceClient device;

    public ReconnectValidator(PhmpConfig config, DeviceClient device) {
        this.config = config;
        this.device = device;
    }

    public ValidationResult validate() {
        List<String> details = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        DeviceClient.ReconnectResult result = device.exerciseReconnect(config.phmp.timeouts.reconnectSeconds);
        details.add("Disconnect observed=" + result.disconnectObserved());
        details.add("Reconnect scheduled=" + result.reconnectScheduled());
        details.add("Recovered=" + result.recovered());
        details.add("Recovery ms=" + result.recoveryMs());

        if (!result.disconnectObserved()) {
            failures.add("Controlled disconnect was not observed");
        }
        if (!result.reconnectScheduled()) {
            failures.add("Reconnect schedule signal missing");
        }
        if (!result.recovered()) {
            failures.add("Socket did not recover within " + config.phmp.timeouts.reconnectSeconds + "s");
        }
        if (!device.getWebsocketState().connected()) {
            failures.add("WebSocket not connected after reconnect exercise");
        }
        if (!device.isAuthenticated()) {
            failures.add("Authentication not re-established after reconnect");
        }

        if (!failures.isEmpty()) {
            return ValidationResult.fail("Disconnect / Reconnect Validation", String.join("; ", failures), failures);
        }
        return ValidationResult.pass(
                "Disconnect / Reconnect Validation",
                "Controlled disconnect recovered with reconnect schedule",
                details,
                Map.of("recoveryMs", result.recoveryMs(), "recovered", true)
        );
    }
}
