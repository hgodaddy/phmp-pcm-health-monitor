package com.poynt.phmp.device;

import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.logs.ScenarioLogAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic local simulation of a healthy PCM device for POC demos without hardware.
 */
public class SimulatedDeviceClient implements DeviceClient {

    private static final Logger log = LoggerFactory.getLogger(SimulatedDeviceClient.class);

    private final PhmpConfig config;
    private final PhmpConfig.RegionConfig regionConfig;
    private final Map<String, Long> pendingMessages = new ConcurrentHashMap<>();
    private boolean booted = true;
    private boolean connected = true;
    private final AtomicLong connectedSince = new AtomicLong(Instant.now().toEpochMilli());

    public SimulatedDeviceClient(PhmpConfig config, PhmpConfig.RegionConfig regionConfig) {
        this.config = config;
        this.regionConfig = regionConfig;
        log.info("Simulated device ready: {} ({})", regionConfig.device().label, regionConfig.device().serial);
    }

    @Override
    public String serial() {
        return regionConfig.device().serial;
    }

    @Override
    public String region() {
        return regionConfig.code();
    }

    @Override
    public List<String> discoverDevices() {
        return List.of(serial());
    }

    @Override
    public boolean isOnline() {
        return true;
    }

    @Override
    public void reboot() {
        booted = false;
        connected = false;
        log.info("[{}] Simulated reboot issued", serial());
        sleep(200);
        booted = true;
        connected = true;
        connectedSince.set(Instant.now().toEpochMilli());
    }

    @Override
    public boolean waitForBoot(int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (booted) {
                return true;
            }
            sleep(100);
        }
        return booted;
    }

    @Override
    public PackageInfo getPackageInfo(String packageName) {
        boolean installed = config.phmp.build.packageName.equals(packageName);
        return new PackageInfo(packageName, "1.0.0-sim", 100, installed);
    }

    @Override
    public boolean isPrivilegedApp(String packageName) {
        return config.phmp.build.packageName.equals(packageName) && config.phmp.build.privileged;
    }

    @Override
    public Optional<String> getSharedUid(String packageName) {
        if (config.phmp.build.packageName.equals(packageName)) {
            return Optional.of(config.phmp.build.sharedUid);
        }
        return Optional.empty();
    }

    @Override
    public List<String> getAbis() {
        return List.of(config.phmp.build.expectedAbi, "armeabi-v7a");
    }

    @Override
    public boolean hasValidCertificate(String packageName) {
        return config.phmp.build.packageName.equals(packageName);
    }

    @Override
    public String certificateFingerprint(String packageName) {
        return hasValidCertificate(packageName) ? config.phmp.build.expectedCertFingerprint : "";
    }

    @Override
    public boolean isBootCompleted() {
        return booted;
    }

    @Override
    public Optional<Integer> findProcessPid(String processName) {
        return Optional.of(4242);
    }

    @Override
    public boolean isServiceRunning(String serviceComponent) {
        return serviceComponent.toLowerCase().contains("pcm");
    }

    @Override
    public boolean hasStartupProvider(String packageName) {
        return true;
    }

    @Override
    public boolean hasNetworkCallback(String packageName) {
        return true;
    }

    @Override
    public long processUptimeMs(String packageName) {
        return 60_000L;
    }

    @Override
    public long deviceUptimeMs() {
        return 65_000L;
    }

    @Override
    public boolean hasStartupHooks(String packageName) {
        return true;
    }

    @Override
    public DeploymentState getDeploymentState() {
        return new DeploymentState(
                config.phmp.deployment.flashSupported,
                config.phmp.deployment.otaSupported,
                config.phmp.deployment.expectedImageChannel,
                "phmp-sim-" + region().toLowerCase() + "-build",
                config.phmp.deployment.verifyBootAfterDeploy && booted
        );
    }

    @Override
    public WebsocketState getWebsocketState() {
        return new WebsocketState(
                true,
                connected,
                selectedEndpoint(),
                new WebsocketState.Instantish(connectedSince.get())
        );
    }

    @Override
    public String selectedEndpoint() {
        return regionConfig.websocket().endpoint;
    }

    @Override
    public String selectedDiscovery() {
        return regionConfig.websocket().discovery;
    }

    @Override
    public boolean isAuthenticated() {
        return connected;
    }

    @Override
    public String authTokenHint() {
        return connected ? "sim-token-" + region().toLowerCase() : "";
    }

    @Override
    public HealthSnapshot collectHealthSnapshot(int monitorSeconds) {
        sleep(Math.min(monitorSeconds, 2) * 200L);
        long duration = Math.max(1, (System.currentTimeMillis() - connectedSince.get()) / 1000);
        return new HealthSnapshot(duration, 0, 0, 0, 0);
    }

    @Override
    public PingStats collectPingStats(int monitorSeconds) {
        sleep(Math.min(monitorSeconds, 2) * 150L);
        return new PingStats(
                config.phmp.websocket.pingIntervalMs,
                config.phmp.websocket.pingIntervalMs,
                120L,
                0
        );
    }

    @Override
    public ReconnectResult exerciseReconnect(int timeoutSeconds) {
        long started = System.currentTimeMillis();
        connected = false;
        log.info("[{}] Simulated transport disconnect", serial());
        sleep(180);
        boolean scheduled = true;
        connected = true;
        connectedSince.set(Instant.now().toEpochMilli());
        long recoveryMs = System.currentTimeMillis() - started;
        boolean recovered = recoveryMs <= timeoutSeconds * 1000L && getWebsocketState().connected();
        log.info("[{}] Simulated reconnect recovered={} recoveryMs={}", serial(), recovered, recoveryMs);
        return new ReconnectResult(true, scheduled, recovered, recoveryMs);
    }

    public void registerInjectedMessage(String correlationId, long injectedAtEpochMs) {
        pendingMessages.put(correlationId, injectedAtEpochMs);
    }

    @Override
    public MessageDeliveryResult awaitCloudMessage(String correlationId, long injectedAtEpochMs, int timeoutSeconds) {
        Long registered = pendingMessages.get(correlationId);
        long injectedAt = registered != null ? registered : injectedAtEpochMs;
        sleep(250);
        long delivered = injectedAt + 180;
        long processed = delivered + 90;
        return new MessageDeliveryResult(true, true, injectedAt, delivered, processed, correlationId);
    }

    @Override
    public String collectLogs(String outputDir) {
        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);
            Path file = dir.resolve("logcat-" + region() + "-" + serial() + ".txt");
            String content = """
                    ---- PHMP simulated logcat ----
                    region=%s
                    serial=%s
                    package=%s
                    websocket=%s
                    discovery=%s
                    auth=success token=%s
                    flash=verified ota=verified channel=%s
                    status=healthy
                    timestamp=%s
                    """.formatted(
                    region(),
                    serial(),
                    config.phmp.build.packageName,
                    selectedEndpoint(),
                    selectedDiscovery(),
                    authTokenHint(),
                    config.phmp.deployment.expectedImageChannel,
                    Instant.now()
            );
            Files.writeString(file, content);
            return file.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write simulated logs", e);
        }
    }

    @Override
    public String shell(String command) {
        return "sim-ok:" + command;
    }

    @Override
    public void beginRunWindow() {
        connectedSince.set(Instant.now().toEpochMilli());
    }

    @Override
    public long deviceEpochMs() {
        return System.currentTimeMillis();
    }

    @Override
    public ScenarioLogAnalyzer logAnalyzer() {
        // Simulate mode never exercises the log-analysis gates; they are all guarded by isSimulate().
        return new ScenarioLogAnalyzer("");
    }

    @Override
    public void close() {
        log.debug("Closing simulated device {}", serial());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
