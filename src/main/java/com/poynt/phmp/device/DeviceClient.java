package com.poynt.phmp.device;

import com.poynt.phmp.logs.ScenarioLogAnalyzer;

import java.util.List;
import java.util.Optional;

/**
 * Abstraction over physical ADB devices and the local simulation backend.
 */
public interface DeviceClient extends AutoCloseable {

    String serial();

    String region();

    List<String> discoverDevices();

    boolean isOnline();

    void reboot();

    boolean waitForBoot(int timeoutSeconds);

    PackageInfo getPackageInfo(String packageName);

    boolean isPrivilegedApp(String packageName);

    Optional<String> getSharedUid(String packageName);

    List<String> getAbis();

    boolean hasValidCertificate(String packageName);

    String certificateFingerprint(String packageName);

    boolean isBootCompleted();

    Optional<Integer> findProcessPid(String processName);

    boolean isServiceRunning(String serviceComponent);

    boolean hasStartupProvider(String packageName);

    boolean hasNetworkCallback(String packageName);

    /**
     * Elapsed run time of the package's process in millis, or 0 when unknown. Used to tell a missing
     * startup log line apart from a process that simply started before the log buffer begins.
     */
    long processUptimeMs(String packageName);

    /** Time since boot in millis, or 0 when unknown. */
    long deviceUptimeMs();

    boolean hasStartupHooks(String packageName);

    DeploymentState getDeploymentState();

    WebsocketState getWebsocketState();

    String selectedEndpoint();

    String selectedDiscovery();

    boolean isAuthenticated();

    String authTokenHint();

    HealthSnapshot collectHealthSnapshot(int monitorSeconds);

    PingStats collectPingStats(int monitorSeconds);

    /**
     * Forces a transport disconnect and waits for recovery (Phase 1 reconnect scenario).
     */
    ReconnectResult exerciseReconnect(int timeoutSeconds);

    MessageDeliveryResult awaitCloudMessage(String correlationId, long injectedAtEpochMs, int timeoutSeconds);

    String collectLogs(String outputDir);

    String shell(String command);

    /**
     * Marks the start of the current run on the device's own clock. Connectivity evidence produced
     * before this point is treated as history rather than as a result of this run.
     */
    void beginRunWindow();

    /** Device-side epoch millis, so log timestamps and run boundaries share one clock. */
    long deviceEpochMs();

    /**
     * Window-aware view of the device log, cached until the client next changes device state. Validators
     * share one snapshot instead of each pulling their own full logcat dump.
     */
    ScenarioLogAnalyzer logAnalyzer();

    @Override
    void close();

    record PackageInfo(String packageName, String versionName, int versionCode, boolean installed) {
    }

    record DeploymentState(
            boolean flashVerified,
            boolean otaVerified,
            String imageChannel,
            String buildFingerprint,
            boolean bootVerifiedAfterDeploy
    ) {
    }

    record WebsocketState(boolean initialized, boolean connected, String endpoint, Instantish connectedSince) {
        public record Instantish(long epochMillis) {
        }
    }

    record HealthSnapshot(
            long connectedDurationSeconds,
            int disconnects,
            int reconnects,
            int handshakeFailures,
            int authFailures
    ) {
    }

    record PingStats(
            int pingIntervalMs,
            int pongIntervalMs,
            long latencyMs,
            int missingHeartbeats
    ) {
    }

    record ReconnectResult(
            boolean disconnectObserved,
            boolean reconnectScheduled,
            boolean recovered,
            long recoveryMs
    ) {
    }

    record MessageDeliveryResult(
            boolean delivered,
            boolean processed,
            long injectedAtEpochMs,
            long deliveredAtEpochMs,
            long processedAtEpochMs,
            String correlationId
    ) {
    }
}
