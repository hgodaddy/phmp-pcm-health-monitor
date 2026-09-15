package com.poynt.phmp.device;

import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.logs.DeviceToken;
import com.poynt.phmp.logs.ScenarioLogAnalyzer;
import com.poynt.phmp.util.CommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ADB-backed device client for lab execution.
 *
 * <p>Socket, auth and reconnect state are derived from {@link ScenarioLogAnalyzer} markers rather than
 * loose substring matching, because substrings such as {@code "connected"} also match
 * {@code "disconnected"} and would report a dead socket as healthy.
 */
public class AdbDeviceClient implements DeviceClient {

    private static final Logger log = LoggerFactory.getLogger(AdbDeviceClient.class);
    private static final Pattern PID_PATTERN = Pattern.compile("(\\S+)\\s+(\\d+)\\s+");
    private static final String FULL_LOGCAT = "logcat -d -v threadtime";
    private static final String DEVICE_CLOCK_COMMAND = "date \"+%m-%d %H:%M:%S.000\"";

    private final PhmpConfig config;
    private final PhmpConfig.RegionConfig regionConfig;
    private final int timeoutSeconds;

    private long runWindowStartMs;
    private int stateEpoch;
    private int cachedEpoch = -1;
    private String cachedDump;
    private ScenarioLogAnalyzer cachedAnalyzer;

    public AdbDeviceClient(PhmpConfig config, PhmpConfig.RegionConfig regionConfig) {
        this.config = config;
        this.regionConfig = regionConfig;
        this.timeoutSeconds = config.phmp.timeouts.adbCommandSeconds;
        if (!isOnline()) {
            throw new IllegalStateException("Device not online via ADB: " + serial());
        }
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
        CommandRunner.CommandResult result = adbGlobal("devices");
        List<String> devices = new ArrayList<>();
        for (String line : result.output().split("\\R")) {
            if (line.endsWith("\tdevice")) {
                devices.add(line.split("\\t")[0].trim());
            }
        }
        return devices;
    }

    @Override
    public boolean isOnline() {
        return discoverDevices().contains(serial());
    }

    @Override
    public void reboot() {
        adb("reboot");
        invalidateLogCache();
    }

    @Override
    public boolean waitForBoot(int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            CommandRunner.CommandResult wait = adb("wait-for-device");
            if (wait.isSuccess() && isBootCompleted()) {
                return true;
            }
            sleep(2000);
        }
        return isBootCompleted();
    }

    @Override
    public void beginRunWindow() {
        // Best effort: a larger buffer reduces the chance of losing boot evidence to rotation mid-run.
        adbShell("logcat -G 16M");
        this.runWindowStartMs = deviceEpochMs();
        invalidateLogCache();
        log.info("[{}] Run window opened at device timestamp {}", serial(), runWindowStartMs);
    }

    @Override
    public long deviceEpochMs() {
        CommandRunner.CommandResult result = adbShell(DEVICE_CLOCK_COMMAND);
        if (result.isSuccess()) {
            long parsed = ScenarioLogAnalyzer.epochMsOfDeviceTimestamp(result.output().trim());
            if (parsed > 0) {
                return parsed;
            }
        }
        log.warn("[{}] Falling back to host clock; device clock read failed", serial());
        return System.currentTimeMillis();
    }

    @Override
    public ScenarioLogAnalyzer logAnalyzer() {
        if (cachedAnalyzer == null || cachedEpoch != stateEpoch) {
            cachedDump = adbShell(FULL_LOGCAT).output();
            cachedAnalyzer = new ScenarioLogAnalyzer(cachedDump, runWindowStartMs);
            cachedEpoch = stateEpoch;
        }
        return cachedAnalyzer;
    }

    @Override
    public PackageInfo getPackageInfo(String packageName) {
        CommandRunner.CommandResult result = adbShell("dumpsys package " + packageName);
        boolean installed = result.isSuccess() && result.output().contains("Package [" + packageName + "]");
        String versionName = extract(result.output(), "versionName=(\\S+)");
        String versionCodeRaw = extract(result.output(), "versionCode=(\\d+)");
        int versionCode = versionCodeRaw == null ? -1 : Integer.parseInt(versionCodeRaw);
        return new PackageInfo(packageName, versionName == null ? "unknown" : versionName, versionCode, installed);
    }

    @Override
    public boolean isPrivilegedApp(String packageName) {
        CommandRunner.CommandResult result = adbShell("dumpsys package " + packageName + " | grep -E 'pkgFlags|privApp|PRIVILEGED'");
        String out = result.output().toLowerCase();
        return out.contains("privileged") || out.contains("privapp");
    }

    @Override
    public Optional<String> getSharedUid(String packageName) {
        CommandRunner.CommandResult result = adbShell("dumpsys package " + packageName + " | grep sharedUser=");
        // dumpsys prints sharedUser=SharedUserSetting{4839d2e android.uid.poynt/2500}; the useful part is
        // the uid name inside the braces, not the wrapper class name a bare \S+ would capture.
        String shared = extract(result.output(), "(android\\.uid\\.[A-Za-z0-9_.]+)");
        if (shared == null) {
            shared = extract(result.output(), "sharedUser=(\\S+)");
        }
        return Optional.ofNullable(shared);
    }

    @Override
    public List<String> getAbis() {
        CommandRunner.CommandResult result = adbShell("getprop ro.product.cpu.abilist");
        if (!result.isSuccess() || result.output().isBlank()) {
            return List.of();
        }
        return Arrays.stream(result.output().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override
    public boolean hasValidCertificate(String packageName) {
        CommandRunner.CommandResult result = adbShell("dumpsys package " + packageName + " | grep -i signatures");
        return result.isSuccess() && !result.output().isBlank() && !result.output().toLowerCase().contains("null");
    }

    @Override
    public String certificateFingerprint(String packageName) {
        CommandRunner.CommandResult result = adbShell("dumpsys package " + packageName + " | grep -iE 'SHA-?256|signatures'");
        String fingerprint = extract(result.output(), "([A-Fa-f0-9:]{32,})");
        return fingerprint == null ? "" : fingerprint;
    }

    @Override
    public boolean isBootCompleted() {
        CommandRunner.CommandResult result = adbShell("getprop sys.boot_completed");
        return result.isSuccess() && "1".equals(result.output().trim());
    }

    @Override
    public Optional<Integer> findProcessPid(String processName) {
        CommandRunner.CommandResult result = adbShell("ps -A | grep " + processName);
        Matcher matcher = PID_PATTERN.matcher(result.output());
        if (matcher.find()) {
            try {
                return Optional.of(Integer.parseInt(matcher.group(2)));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean isServiceRunning(String serviceComponent) {
        CommandRunner.CommandResult result = adbShell("dumpsys activity services " + serviceComponent);
        return result.isSuccess() && result.output().contains(serviceComponent);
    }

    @Override
    public boolean hasStartupProvider(String packageName) {
        CommandRunner.CommandResult result = adbShell("dumpsys package " + packageName + " | grep -i Provider");
        return result.isSuccess() && result.output().toLowerCase().contains("provider");
    }

    /**
     * ConnectivityService records NetworkRequests against the caller's uid and never against its package
     * name, so this resolves the uid first. Grepping for the package name can only ever return nothing.
     */
    @Override
    public boolean hasNetworkCallback(String packageName) {
        Optional<Integer> uid = appUid(packageName);
        if (uid.isEmpty()) {
            return false;
        }
        CommandRunner.CommandResult result = adbShell(
                "dumpsys connectivity | grep -E 'Uid: " + uid.get() + "\\]'");
        return result.isSuccess() && !result.output().isBlank();
    }

    private Optional<Integer> appUid(String packageName) {
        CommandRunner.CommandResult result = adbShell("dumpsys package " + packageName + " | grep -E 'userId='");
        String uid = extract(result.output(), "userId=(\\d+)");
        if (uid == null) {
            // Shared-uid system apps report the uid alongside the shared user name instead.
            CommandRunner.CommandResult shared = adbShell(
                    "dumpsys package " + packageName + " | grep sharedUser=");
            uid = extract(shared.output(), "android\\.uid\\.[A-Za-z0-9_.]+/(\\d+)");
        }
        try {
            return uid == null ? Optional.empty() : Optional.of(Integer.parseInt(uid));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Elapsed run time of the package's process, parsed from {@code ps -o ETIME} ([[DD-]HH:]MM:SS).
     * Zero when the process is absent or the field cannot be read.
     */
    @Override
    public long processUptimeMs(String packageName) {
        CommandRunner.CommandResult result = adbShell("ps -A -o ETIME,NAME | grep " + packageName);
        if (!result.isSuccess()) {
            return 0L;
        }
        String elapsed = extract(result.output(), "((?:\\d+-)?(?:\\d+:)?\\d+:\\d{2})");
        if (elapsed == null) {
            return 0L;
        }
        long days = 0;
        String remainder = elapsed;
        int dash = elapsed.indexOf('-');
        if (dash > 0) {
            days = Long.parseLong(elapsed.substring(0, dash));
            remainder = elapsed.substring(dash + 1);
        }
        String[] parts = remainder.split(":");
        long hours = parts.length == 3 ? Long.parseLong(parts[0]) : 0;
        long minutes = Long.parseLong(parts[parts.length - 2]);
        long seconds = Long.parseLong(parts[parts.length - 1]);
        return ((days * 24 + hours) * 3600 + minutes * 60 + seconds) * 1000L;
    }

    @Override
    public long deviceUptimeMs() {
        CommandRunner.CommandResult result = adbShell("cat /proc/uptime");
        String seconds = extract(result.output(), "^(\\d+)");
        if (seconds == null) {
            return 0L;
        }
        try {
            return Long.parseLong(seconds) * 1000L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Override
    public boolean hasStartupHooks(String packageName) {
        CommandRunner.CommandResult result = adbShell("dumpsys package " + packageName + " | grep -iE 'BOOT_COMPLETED|LOCKED_BOOT'");
        return result.isSuccess() && !result.output().isBlank();
    }

    @Override
    public DeploymentState getDeploymentState() {
        CommandRunner.CommandResult channel = adbShell("getprop ro.build.flavor");
        CommandRunner.CommandResult fingerprint = adbShell("getprop ro.build.fingerprint");
        CommandRunner.CommandResult ota = adbShell("getprop persist.sys.ota.status");
        String imageChannel = channel.isSuccess() && !channel.output().isBlank()
                ? channel.output().trim()
                : config.phmp.deployment.expectedImageChannel;
        boolean otaVerified = !ota.isSuccess()
                || ota.output().isBlank()
                || ota.output().toLowerCase().contains("success")
                || ota.output().toLowerCase().contains("idle");
        boolean bootOk = !config.phmp.deployment.verifyBootAfterDeploy || isBootCompleted();
        return new DeploymentState(true, otaVerified, imageChannel,
                fingerprint.isSuccess() ? fingerprint.output().trim() : "unknown", bootOk);
    }

    @Override
    public WebsocketState getWebsocketState() {
        ScenarioLogAnalyzer transitions = transitionAnalyzer();
        boolean initialized = transitions.hasWebsocketInitSignals();
        boolean connected = transitions.connectedAt(Long.MAX_VALUE);
        return new WebsocketState(initialized, connected, selectedEndpoint(),
                new WebsocketState.Instantish(transitions.lastTimestamp()));
    }

    @Override
    public String selectedEndpoint() {
        // Discovery wins. PCM asks the discovery service for cloudMessageService and dials whatever it
        // returns; persist.poynt.srvc.url.pcm is only a seed and is left stale by an environment switch.
        Optional<String> discovered = logAnalyzer().latestDiscoveredHost();
        if (discovered.isPresent()) {
            return discovered.get();
        }
        CommandRunner.CommandResult serviceProp = adbShell("getprop persist.poynt.srvc.url.pcm");
        if (serviceProp.isSuccess() && !serviceProp.output().isBlank()) {
            return serviceProp.output().trim();
        }
        CommandRunner.CommandResult legacyProp = adbShell("getprop persist.poynt.pcm.endpoint");
        if (legacyProp.isSuccess() && !legacyProp.output().isBlank()) {
            return legacyProp.output().trim();
        }
        return regionConfig.websocket().endpoint;
    }

    @Override
    public String selectedDiscovery() {
        CommandRunner.CommandResult result = adbShell("getprop persist.poynt.pcm.discovery");
        if (result.isSuccess() && !result.output().isBlank()) {
            return result.output().trim();
        }
        return regionConfig.websocket().discovery;
    }

    @Override
    public boolean isAuthenticated() {
        return authAnalyzer().authenticatedBySocketHandshake();
    }

    @Override
    public String authTokenHint() {
        // Report only that authentication succeeded; the token value itself must never reach a report.
        Optional<DeviceToken> token = logAnalyzer().deviceToken();
        if (token.isPresent()) {
            return token.get().summary();
        }
        ScenarioLogAnalyzer analyzer = authAnalyzer();
        if (!analyzer.authenticatedBySocketHandshake()) {
            return "";
        }
        return analyzer.tokenPath().tokenFetchSuccess()
                ? "access-token-acquired"
                : "authenticated-via-socket-handshake";
    }

    @Override
    public HealthSnapshot collectHealthSnapshot(int monitorSeconds) {
        sleep(Math.max(1, monitorSeconds) * 1000L);
        invalidateLogCache();
        ScenarioLogAnalyzer analyzer = logAnalyzer();
        ScenarioLogAnalyzer.ConnectivityStats stats = analyzer.connectivityStats();
        int authFailures = analyzer.tokenPath().tokenFailure() ? 1 : 0;
        return new HealthSnapshot(
                stats.connectedDurationMs() / 1000L,
                stats.disconnects(),
                stats.reconnects(),
                stats.errorSignals(),
                authFailures
        );
    }

    @Override
    public PingStats collectPingStats(int monitorSeconds) {
        sleep(Math.max(1, monitorSeconds) * 1000L);
        invalidateLogCache();
        ScenarioLogAnalyzer analyzer = logAnalyzer();
        int configured = config.phmp.websocket.pingIntervalMs;
        long detected = analyzer.detectedPingProfileMs(configured);
        List<Long> pings = analyzer.pingEventTimes();
        int missing = pings.size() >= 2 && analyzer.maxGapMs(pings) > 2 * detected ? 1 : 0;
        long latency = analyzer.latestLatencyMs();
        if (latency == 0) {
            latency = analyzer.pingPongLatencyMs();
        }
        return new PingStats(configured, configured, latency, missing);
    }

    @Override
    public ReconnectResult exerciseReconnect(int timeoutSeconds) {
        long broadcastAt = deviceEpochMs();
        long started = System.currentTimeMillis();
        adbShell("am broadcast -a co.poynt.pcm.ACTION_FORCE_DISCONNECT");
        invalidateLogCache();

        boolean disconnectObserved = false;
        boolean reconnectScheduled = false;
        boolean recovered = false;
        long deadline = started + timeoutSeconds * 1000L;

        while (System.currentTimeMillis() < deadline) {
            // Only evidence timestamped after the broadcast counts, otherwise a disconnect from an
            // earlier boot would satisfy this gate without the broadcast having had any effect.
            ScenarioLogAnalyzer transitions = transitionAnalyzer();
            if (transitions.hasDisconnectAfter(broadcastAt)) {
                disconnectObserved = true;
            }
            if (transitions.hasReconnectScheduledAfter(broadcastAt)) {
                reconnectScheduled = true;
            }
            if (disconnectObserved && transitions.hasConnectedAfter(broadcastAt)) {
                recovered = true;
                break;
            }
            sleep(2000);
        }
        long recoveryMs = System.currentTimeMillis() - started;
        invalidateLogCache();
        return new ReconnectResult(disconnectObserved, reconnectScheduled, recovered, recoveryMs);
    }

    @Override
    public MessageDeliveryResult awaitCloudMessage(String correlationId, long injectedAtEpochMs, int timeoutSeconds) {
        // Timestamps stay on the device clock throughout: the terminal can sit in a different timezone
        // from the host (observed 12.5h apart), which would make host-versus-log latency meaningless.
        long injectedAt = injectedAtEpochMs > 0 ? injectedAtEpochMs : deviceEpochMs();
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        boolean delivered = false;
        boolean processed = false;
        long deliveredAt = 0;
        long processedAt = 0;
        while (System.currentTimeMillis() < deadline) {
            invalidateLogCache();
            ScenarioLogAnalyzer.CloudPath path = logAnalyzer().cloudPath(correlationId);
            if (!delivered && (path.rawSocketSignal() || path.messageReceived())) {
                delivered = true;
                deliveredAt = path.receivedAtEpochMs() > 0 ? path.receivedAtEpochMs() : deviceEpochMs();
            }
            if (path.eventLogReceived() || path.paymentBridgePath() || path.outboundBroadcastPath()) {
                processed = true;
                processedAt = deviceEpochMs();
                break;
            }
            sleep(2000);
        }
        return new MessageDeliveryResult(delivered, processed, injectedAt, deliveredAt, processedAt, correlationId);
    }

    @Override
    public String collectLogs(String outputDir) {
        try {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);
            Path file = dir.resolve("logcat-" + region() + "-" + serial() + ".txt");
            logAnalyzer();
            Files.writeString(file, cachedDump == null ? "" : cachedDump);
            return file.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist logcat", e);
        }
    }

    @Override
    public String shell(String command) {
        return adbShell(command).output();
    }

    @Override
    public void close() {
        log.debug("ADB client closed for {}", serial());
    }

    /**
     * Connect/disconnect/reconnect lines only. The filter runs on the device so polling loops transfer
     * a few hundred lines instead of the whole buffer.
     */
    private ScenarioLogAnalyzer transitionAnalyzer() {
        String pattern = String.join("|", ScenarioLogAnalyzer.TRANSITION_MARKERS);
        CommandRunner.CommandResult result = adbShell(FULL_LOGCAT + " | grep -aiE '" + pattern + "'");
        return new ScenarioLogAnalyzer(result.output(), runWindowStartMs);
    }

    private ScenarioLogAnalyzer authAnalyzer() {
        CommandRunner.CommandResult result = adbShell(FULL_LOGCAT
                + " | grep -aiE 'access token|failed to obtain token|maximum retries reached"
                + "|starting pcm connection|is websocket open'");
        return new ScenarioLogAnalyzer(result.output(), runWindowStartMs);
    }

    private void invalidateLogCache() {
        stateEpoch++;
    }

    private CommandRunner.CommandResult adb(String... args) {
        List<String> command = new ArrayList<>();
        command.add("adb");
        command.add("-s");
        command.add(serial());
        command.addAll(Arrays.asList(args));
        return CommandRunner.run(command, timeoutSeconds);
    }

    private CommandRunner.CommandResult adbShell(String shellCommand) {
        return adb("shell", shellCommand);
    }

    private CommandRunner.CommandResult adbGlobal(String... args) {
        List<String> command = new ArrayList<>();
        command.add("adb");
        command.addAll(Arrays.asList(args));
        return CommandRunner.run(command, timeoutSeconds);
    }

    private static String extract(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
