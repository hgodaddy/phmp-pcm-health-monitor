package com.poynt.phmp.validation.startup;

import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.device.DeviceClient;
import com.poynt.phmp.logs.ScenarioLogAnalyzer;
import com.poynt.phmp.model.ValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class StartupValidator {

    /** PCM is expected up with the system; boot-to-launch beyond this is treated as not auto-started. */
    private static final long BOOT_LAUNCH_TOLERANCE_MS = 5 * 60_000L;

    private final PhmpConfig config;
    private final DeviceClient device;

    public StartupValidator(PhmpConfig config, DeviceClient device) {
        this.config = config;
        this.device = device;
    }

    public ValidationResult validate() {
        String packageName = config.phmp.build.packageName;
        List<String> details = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        if (!device.waitForBoot(config.phmp.timeouts.bootSeconds) || !device.isBootCompleted()) {
            failures.add("Boot completion not observed within timeout");
        } else {
            details.add("Boot completed");
        }

        Optional<Integer> pid = device.findProcessPid("pcm");
        if (pid.isEmpty()) {
            // fallback to package process name fragment
            pid = device.findProcessPid(packageName);
        }
        if (pid.isEmpty()) {
            failures.add("PCM process not found");
        } else {
            details.add("PCM process pid=" + pid.get());
        }

        String service = packageName + "/.PcmService";
        if (!device.isServiceRunning(service) && !device.isServiceRunning("PcmService")) {
            failures.add("PCM service not running");
        } else {
            details.add("PCM service running");
        }

        if (!device.hasStartupProvider(packageName)) {
            failures.add("Startup provider missing");
        } else {
            details.add("Startup provider present");
        }

        if (!device.hasNetworkCallback(packageName)) {
            failures.add("Network callback not registered");
        } else {
            details.add("Network callback registered");
        }

        if (!device.hasStartupHooks(packageName)) {
            failures.add("Startup hooks missing");
        } else {
            details.add("Startup hooks present");
        }

        if (!config.isSimulate()) {
            ScenarioLogAnalyzer analyzer = device.logAnalyzer();
            // Auto-start evidence, in descending order of directness. The ActivityManager "Start proc"
            // line is preferred but is not emitted for PCM on every build (PST3 develop-1.26.02.78 emits
            // neither the text line nor the am_proc_start event), so process-versus-boot timing is used
            // as the fallback: a PCM process as old as the system itself was not started by hand.
            long launchAfterBootMs = launchDelayAfterBootMs();
            if (analyzer.hasStartProcCloudMessaging()) {
                details.add("Auto-start process launch observed");
            } else if (launchAfterBootMs >= 0 && launchAfterBootMs <= BOOT_LAUNCH_TOLERANCE_MS) {
                details.add("Auto-start inferred: PCM process started " + (launchAfterBootMs / 1000)
                        + "s into device uptime");
            } else if (startedBeforeBuffer(analyzer)) {
                // A long-lived PCM process launched before the oldest retained log line. Absence of the
                // "Start proc" line is then a limit of the ring buffer, not evidence of a failed auto-start.
                details.add("Auto-start not observable: PCM process predates the retained logcat buffer");
            } else {
                failures.add("PCM did not auto-start: no launch evidence at boot for " + packageName);
            }
            if (!analyzer.hasStartupMarker()) {
                failures.add("Auto-start startup marker missing (PCM-STARTUP/provider/boot/network)");
            } else {
                details.add("Startup marker observed");
            }
            if (!analyzer.hasServiceOnCreate()) {
                failures.add("Startup window missing PcmService onCreate");
            } else {
                details.add("PcmService onCreate observed");
            }
            if (!analyzer.hasConnectTaskStart() || !analyzer.hasConnectAttempt()) {
                failures.add("Startup window missing websocket connect task + attempt");
            } else {
                details.add("WebSocket connect task and attempt observed");
            }
            if (analyzer.hasOnlyManualStartService()) {
                failures.add("Only adb startservice workaround observed without auto-start markers");
            }
            if (analyzer.hasServiceOnStartCommand()) {
                details.add("PcmService onStartCommand observed");
            }
        }

        if (!failures.isEmpty()) {
            return ValidationResult.fail("Startup Validation", String.join("; ", failures), failures);
        }
        return ValidationResult.pass(
                "Startup Validation",
                "Boot, process, service, provider, network callback, and hooks validated",
                details,
                Map.of("pid", pid.orElse(-1))
        );
    }

    /**
     * How long after boot the PCM process appeared, or -1 when either uptime is unavailable. A process
     * whose age matches the device's own age came up with the system rather than from adb.
     */
    private long launchDelayAfterBootMs() {
        long processUptime = device.processUptimeMs(config.phmp.build.packageName);
        long deviceUptime = device.deviceUptimeMs();
        if (processUptime <= 0 || deviceUptime <= 0) {
            return -1;
        }
        return Math.max(0, deviceUptime - processUptime);
    }

    private boolean startedBeforeBuffer(ScenarioLogAnalyzer analyzer) {
        long uptimeMs = device.processUptimeMs(config.phmp.build.packageName);
        long oldestLine = analyzer.firstTimestamp();
        if (uptimeMs <= 0 || oldestLine <= 0) {
            return false;
        }
        return device.deviceEpochMs() - uptimeMs < oldestLine;
    }
}
