package com.poynt.phmp.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.device.DeviceClient;
import com.poynt.phmp.model.ValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Release-blocking IoT companion check. Simulate mode records a passing stand-in so the existing
 * US/EU POC stays green. Device mode broadcasts {@code RELEASE_GATE} and reads
 * {@code iot-release-gate.json} (never a raw GD token).
 */
public class IotCompanionValidator {

    public static final String CHECK_NAME = "IoT Companion Gate";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PhmpConfig config;
    private final DeviceClient device;

    public IotCompanionValidator(PhmpConfig config, DeviceClient device) {
        this.config = config;
        this.device = device;
    }

    public ValidationResult validate() {
        if (!config.phmp.iot.enabled) {
            return ValidationResult.skip(
                    CHECK_NAME,
                    "Not evaluated: phmp.iot.enabled=false",
                    List.of("Set phmp.iot.enabled=true to make IoT companion release-blocking"));
        }
        if (config.isSimulate()) {
            return ValidationResult.pass(
                    CHECK_NAME,
                    "Simulated companion RELEASE_GATE PASS",
                    List.of(
                            "mode=simulate",
                            "companionPackage=" + config.phmp.iot.companionPackage,
                            "action=" + config.phmp.iot.action),
                    Map.of("simulated", true, "releaseReady", true));
        }
        return validateOnDevice();
    }

    private ValidationResult validateOnDevice() {
        List<String> details = new ArrayList<>();
        String pkg = config.phmp.iot.companionPackage;
        DeviceClient.PackageInfo info = device.getPackageInfo(pkg);
        details.add("serial=" + device.serial());
        details.add("companionInstalled=" + info.installed());
        if (!info.installed()) {
            return ValidationResult.fail(
                    CHECK_NAME,
                    "Companion APK not installed: " + pkg,
                    details);
        }
        details.add("companionVersion=" + info.versionName());

        String files = "/sdcard/Android/data/" + pkg + "/files";
        String remote = files + "/iot-release-gate.json";
        device.shell("rm -f " + remote);
        device.shell("am start -n " + pkg + "/.ui.DashboardActivity");
        String buildId = System.getenv().getOrDefault("BUILD_ID",
                System.getProperty("phmp.buildId", "phmp"));
        int timeoutSec = Math.max(30, config.phmp.iot.gateTimeoutSeconds);
        device.shell("am broadcast -a " + pkg + ".RUN_ACTION"
                + " --es action " + config.phmp.iot.action
                + " --es buildId " + buildId
                + " --ei timeoutSec " + timeoutSec);
        details.add("broadcast action=" + config.phmp.iot.action + " timeoutSec=" + timeoutSec);

        long deadline = System.currentTimeMillis() + (timeoutSec + 30L) * 1000L;
        String json = null;
        while (System.currentTimeMillis() < deadline) {
            String out = device.shell("cat " + remote);
            if (out != null) {
                String trimmed = out.trim();
                if (trimmed.startsWith("{")) {
                    json = trimmed;
                    break;
                }
            }
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ValidationResult.fail(CHECK_NAME, "Interrupted waiting for gate file", details);
            }
        }
        if (json == null) {
            return ValidationResult.skip(
                    CHECK_NAME,
                    "iot-release-gate.json not produced in time",
                    details);
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            boolean skipped = node.path("skipped").asBoolean(false)
                    || "SKIP".equalsIgnoreCase(node.path("status").asText());
            boolean ready = node.path("releaseReady").asBoolean(false)
                    || node.path("passed").asBoolean(false);
            String message = node.path("message").asText("companion gate");
            details.add("status=" + node.path("status").asText());
            details.add("releaseReady=" + node.path("releaseReady").asBoolean(false));
            if (node.has("token") && !node.get("token").asText("").isBlank()) {
                return ValidationResult.fail(
                        CHECK_NAME,
                        "Gate JSON must not include a raw token field",
                        details);
            }
            if (skipped) {
                return ValidationResult.skip(CHECK_NAME, message, details);
            }
            if (ready) {
                return ValidationResult.pass(
                        CHECK_NAME,
                        message,
                        details,
                        Map.of("releaseReady", true));
            }
            return ValidationResult.fail(CHECK_NAME, message, details);
        } catch (Exception e) {
            details.add(e.getMessage() == null ? e.toString() : e.getMessage());
            return ValidationResult.fail(CHECK_NAME, "Invalid iot-release-gate.json", details);
        }
    }
}
