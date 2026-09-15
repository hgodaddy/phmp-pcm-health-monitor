package com.poynt.phmp.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Loads PHMP configuration from application.yaml with system-property / env / local.properties overrides.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PhmpConfig {

    public PhmpSettings phmp = new PhmpSettings();

    public static PhmpConfig load() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream in = PhmpConfig.class.getClassLoader().getResourceAsStream("application.yaml")) {
            if (in == null) {
                throw new IllegalStateException("application.yaml not found on classpath");
            }
            PhmpConfig config = mapper.readValue(in, PhmpConfig.class);
            config.applyLocalProperties();
            config.applyRuntimeOverrides();
            return config;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load application.yaml", e);
        }
    }

    private void applyLocalProperties() {
        Path local = Path.of("local.properties");
        if (!Files.isRegularFile(local)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(local)) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read local.properties", e);
        }
        applyProp(props, "phmp.mode", v -> phmp.mode = v);
        applyProp(props, "phmp.env", v -> phmp.env = v);
        applyProp(props, "phmp.region", v -> phmp.region = v);
        applyProp(props, "phmp.us.serial", v -> phmp.devices.us.serial = v);
        applyProp(props, "phmp.eu.serial", v -> phmp.devices.eu.serial = v);
        applyProp(props, "phmp.cloud.token", v -> phmp.cloud.authHeader = "Bearer " + v);
        applyProp(props, "phmp.cloud.injectEnabled", v -> phmp.cloud.injectEnabled = Boolean.parseBoolean(v));
    }

    private static void applyProp(Properties props, String key, java.util.function.Consumer<String> consumer) {
        String value = props.getProperty(key);
        if (value != null && !value.isBlank()) {
            consumer.accept(value.trim());
        }
    }

    private void applyRuntimeOverrides() {
        String mode = System.getProperty("phmp.mode");
        if (mode != null && !mode.isBlank()) {
            phmp.mode = mode.trim().toLowerCase(Locale.ROOT);
        }
        String env = System.getProperty("phmp.env");
        if (env != null && !env.isBlank()) {
            phmp.env = env.trim().toLowerCase(Locale.ROOT);
        }
        String region = System.getProperty("phmp.region");
        if (region != null && !region.isBlank()) {
            phmp.region = region.trim();
        }
        String injectEnabled = System.getProperty("phmp.cloud.injectEnabled");
        if (injectEnabled != null && !injectEnabled.isBlank()) {
            phmp.cloud.injectEnabled = Boolean.parseBoolean(injectEnabled.trim());
        }
        String token = System.getenv("PHMP_CLOUD_TOKEN");
        if (token != null && !token.isBlank()) {
            phmp.cloud.authHeader = "Bearer " + token;
        }
        String usSerial = System.getenv("PHMP_US_SERIAL");
        if (usSerial != null && !usSerial.isBlank()) {
            phmp.devices.us.serial = usSerial;
        }
        String euSerial = System.getenv("PHMP_EU_SERIAL");
        if (euSerial != null && !euSerial.isBlank()) {
            phmp.devices.eu.serial = euSerial;
        }
    }

    public boolean isSimulate() {
        return "simulate".equalsIgnoreCase(phmp.mode);
    }

    public RegionConfig region(String regionCode) {
        String code = regionCode.toUpperCase(Locale.ROOT);
        DeviceProfile device = switch (code) {
            case "US" -> phmp.devices.us;
            case "EU" -> phmp.devices.eu;
            default -> throw new IllegalArgumentException("Unsupported region: " + regionCode);
        };
        EndpointPair ws = switch (code) {
            case "US" -> phmp.websocket.us;
            case "EU" -> phmp.websocket.eu;
            default -> throw new IllegalArgumentException("Unsupported region: " + regionCode);
        };
        CloudEndpoint cloud = switch (code) {
            case "US" -> phmp.cloud.us;
            case "EU" -> phmp.cloud.eu;
            default -> throw new IllegalArgumentException("Unsupported region: " + regionCode);
        };

        // An environment profile, when defined for the active phmp.env, overrides the defaults above so
        // that dev / ote / prod can be selected with -Dphmp.env instead of by editing tracked YAML.
        EnvironmentProfile profile = environmentProfile(code);
        if (profile != null) {
            ws = profile.toEndpointPair(ws);
            cloud = profile.toCloudEndpoint(cloud);
        }
        return new RegionConfig(code, device, ws, cloud);
    }

    /** Environment profile for the active env and given region, or null when none is configured. */
    public EnvironmentProfile environmentProfile(String regionCode) {
        if (phmp.environments == null || phmp.env == null) {
            return null;
        }
        Map<String, EnvironmentProfile> byRegion =
                phmp.environments.get(phmp.env.trim().toLowerCase(Locale.ROOT));
        if (byRegion == null) {
            return null;
        }
        return byRegion.get(regionCode.toLowerCase(Locale.ROOT));
    }

    public boolean hasEnvironmentProfile(String regionCode) {
        return environmentProfile(regionCode) != null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PhmpSettings {
        public String mode = "simulate";
        public String env = "local";
        public String region = "both";
        public BuildSettings build = new BuildSettings();
        public DeploymentSettings deployment = new DeploymentSettings();
        public TimeoutSettings timeouts = new TimeoutSettings();
        public WebsocketSettings websocket = new WebsocketSettings();
        public CloudSettings cloud = new CloudSettings();
        public DeviceSettings devices = new DeviceSettings();
        public ReportingSettings reporting = new ReportingSettings();
        public LoggingSettings logging = new LoggingSettings();
        public ReleaseGateSettings releaseGate = new ReleaseGateSettings();
        public IotSettings iot = new IotSettings();
        /** env name -> region code (lowercase) -> endpoint profile. */
        public Map<String, Map<String, EnvironmentProfile>> environments = new LinkedHashMap<>();
    }

    /** Per-environment, per-region endpoints. Blank fields fall back to the defaults. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EnvironmentProfile {
        public String endpoint;
        public String discovery;
        public String cloudBaseUrl;
        public String injectPath;
        public String mothershipPath;

        EndpointPair toEndpointPair(EndpointPair fallback) {
            EndpointPair pair = new EndpointPair();
            pair.endpoint = isSet(endpoint) ? endpoint.trim() : fallback.endpoint;
            pair.discovery = isSet(discovery) ? discovery.trim() : fallback.discovery;
            return pair;
        }

        CloudEndpoint toCloudEndpoint(CloudEndpoint fallback) {
            CloudEndpoint endpointConfig = new CloudEndpoint();
            endpointConfig.baseUrl = isSet(cloudBaseUrl) ? cloudBaseUrl.trim() : fallback.baseUrl;
            endpointConfig.injectPath = isSet(injectPath) ? injectPath.trim() : fallback.injectPath;
            endpointConfig.mothershipPath = isSet(mothershipPath) ? mothershipPath.trim() : fallback.mothershipPath;
            return endpointConfig;
        }

        private static boolean isSet(String value) {
            return value != null && !value.isBlank();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BuildSettings {
        public String packageName = "co.poynt.services.pcm";
        public String sharedUid = "android.uid.system";
        public String expectedAbi = "arm64-v8a";
        public boolean privileged = true;
        public String expectedCertFingerprint = "SIM-CERT-FINGERPRINT";
        public int minVersionCode = 1;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DeploymentSettings {
        public boolean flashSupported = true;
        public boolean otaSupported = true;
        public String expectedImageChannel = "nightly-vendor";
        public boolean verifyBootAfterDeploy = true;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TimeoutSettings {
        public int bootSeconds = 120;
        public int websocketConnectSeconds = 60;
        public int healthMonitorSeconds = 30;
        public int cloudMessageSeconds = 45;
        public int adbCommandSeconds = 30;
        public int reconnectSeconds = 45;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WebsocketSettings {
        public EndpointPair us = new EndpointPair();
        public EndpointPair eu = new EndpointPair();
        public int pingIntervalMs = 15000;
        public int pongTimeoutMs = 5000;
        public int maxLatencyMs = 2000;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CloudSettings {
        public CloudEndpoint us = new CloudEndpoint();
        public CloudEndpoint eu = new CloudEndpoint();
        public String authHeader = "Bearer demo-token";
        /**
         * Set false until a real inject endpoint and credentials exist for the target environment.
         * The cloud gate then reports SKIPPED rather than failing on an endpoint we cannot call.
         */
        public boolean injectEnabled = true;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DeviceSettings {
        public DeviceProfile us = new DeviceProfile();
        public DeviceProfile eu = new DeviceProfile();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReportingSettings {
        public String outputDir = "reports";
        public String htmlReportName = "phmp-execution-report.html";
        public String historyDir = "reports/history";
        public boolean retainLogs = true;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LoggingSettings {
        public boolean collectLogcat = true;
        public String logDir = "logs";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReleaseGateSettings {
        public boolean requireUs = true;
        public boolean requireEu = true;
        public boolean blockOnAnyCriticalFailure = true;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IotSettings {
        public boolean enabled = true;
        public String companionPackage = "co.poynt.cloudmessaging.iot.test";
        public String action = "RELEASE_GATE";
        public int gateTimeoutSeconds = 360;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EndpointPair {
        public String endpoint;
        public String discovery;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CloudEndpoint {
        public String baseUrl;
        public String injectPath;
        public String mothershipPath = "/v1/mothership/events";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DeviceProfile {
        public String serial;
        public String label;
    }

    public record RegionConfig(
            String code,
            DeviceProfile device,
            EndpointPair websocket,
            CloudEndpoint cloud
    ) {
        public RegionConfig {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(device, "device");
            Objects.requireNonNull(websocket, "websocket");
            Objects.requireNonNull(cloud, "cloud");
        }
    }

    public Map<String, Object> summary() {
        return Map.of(
                "mode", phmp.mode,
                "env", phmp.env,
                "region", phmp.region,
                "packageName", phmp.build.packageName
        );
    }
}
