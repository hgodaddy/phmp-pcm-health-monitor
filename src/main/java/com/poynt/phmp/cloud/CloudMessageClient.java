package com.poynt.phmp.cloud;

import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.device.DeviceClient;
import com.poynt.phmp.device.SimulatedDeviceClient;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * Injects cloud messages via Rest Assured (device mode) or local simulation hooks.
 */
public class CloudMessageClient {

    private static final Logger log = LoggerFactory.getLogger(CloudMessageClient.class);

    private final PhmpConfig config;
    private final DeviceClient device;

    public CloudMessageClient(PhmpConfig config, DeviceClient device) {
        this.config = config;
        this.device = device;
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    public InjectedMessage injectHealthProbe() {
        String correlationId = "phmp-" + device.region().toLowerCase() + "-" + UUID.randomUUID();
        // Device clock, so this is comparable with timestamps parsed out of the device log.
        long injectedAt = config.isSimulate() ? Instant.now().toEpochMilli() : device.deviceEpochMs();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "PCM_HEALTH_PROBE");
        payload.put("correlationId", correlationId);
        payload.put("region", device.region());
        payload.put("deviceSerial", device.serial());
        payload.put("injectedAt", injectedAt);
        payload.put("source", "PHMP");

        PhmpConfig.CloudEndpoint endpoint = config.region(device.region()).cloud();
        payload.put("mothershipPath", endpoint.mothershipPath);

        if (config.isSimulate()) {
            log.info("Simulating Mothership/cloud inject correlationId={} path={}",
                    correlationId, endpoint.mothershipPath);
            if (device instanceof SimulatedDeviceClient simulated) {
                simulated.registerInjectedMessage(correlationId, injectedAt);
            }
            return new InjectedMessage(correlationId, injectedAt, payload, 200, "SIMULATED");
        }

        String url = endpoint.baseUrl + endpoint.injectPath;
        log.info("Injecting cloud message url={} mothership={} correlationId={}",
                url, endpoint.mothershipPath, correlationId);
        Response response = given()
                .header("Authorization", config.phmp.cloud.authHeader)
                .header("Content-Type", "application/json")
                .body(payload)
                .when()
                .post(url)
                .then()
                .extract()
                .response();
        return new InjectedMessage(correlationId, injectedAt, payload, response.statusCode(), response.asString());
    }

    public record InjectedMessage(
            String correlationId,
            long injectedAtEpochMs,
            Map<String, Object> payload,
            int statusCode,
            String rawResponse
    ) {
        public boolean accepted() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
