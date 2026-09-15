package com.poynt.phmp.cloud;

import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.device.DeviceClient;
import com.poynt.phmp.logs.ScenarioLogAnalyzer;
import com.poynt.phmp.model.ValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CloudMessagingValidator {

    private final PhmpConfig config;
    private final DeviceClient device;
    private final CloudMessageClient cloudMessageClient;
    private final CorrelationEngine correlationEngine;

    public CloudMessagingValidator(PhmpConfig config, DeviceClient device) {
        this.config = config;
        this.device = device;
        this.cloudMessageClient = new CloudMessageClient(config, device);
        this.correlationEngine = new CorrelationEngine();
    }

    public ValidationResult validate() {
        List<String> details = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        if (!config.phmp.cloud.injectEnabled) {
            PhmpConfig.CloudEndpoint endpoint = config.region(device.region()).cloud();
            return ValidationResult.skip(
                    "Cloud Messaging Validation",
                    "Not evaluated: cloud inject is disabled for env=" + config.phmp.env,
                    List.of(
                            "Set phmp.cloud.injectEnabled=true once an inject endpoint and credentials exist",
                            "Configured base URL=" + endpoint.baseUrl,
                            "Configured inject path=" + endpoint.injectPath,
                            "Region=" + device.region(),
                            "This check is release-blocking while unevaluated"));
        }

        CloudMessageClient.InjectedMessage injected = cloudMessageClient.injectHealthProbe();
        details.add("Generated payload correlationId=" + injected.correlationId());
        details.add("Cloud API status=" + injected.statusCode());

        if (!injected.accepted()) {
            failures.add("Cloud API inject failed with status " + injected.statusCode());
            return ValidationResult.fail("Cloud Messaging Validation", String.join("; ", failures), failures);
        }

        DeviceClient.MessageDeliveryResult delivery = device.awaitCloudMessage(
                injected.correlationId(),
                injected.injectedAtEpochMs(),
                config.phmp.timeouts.cloudMessageSeconds
        );

        CorrelationEngine.CorrelationReport report = correlationEngine.correlate(injected, delivery);
        details.addAll(report.details());

        if (!delivery.delivered()) {
            failures.add("Message delivery not observed on device");
        }
        if (!delivery.processed()) {
            failures.add("Message processing not confirmed on device");
        }
        if (!report.aligned()) {
            failures.add("Timestamp correlation failed: " + report.summary());
        }

        if (!config.isSimulate()) {
            ScenarioLogAnalyzer analyzer = device.logAnalyzer();
            ScenarioLogAnalyzer.CloudPath cloudPath = analyzer.cloudPath(injected.correlationId());
            ScenarioLogAnalyzer.TokenPath tokenPath = analyzer.tokenPath();

            details.add("Cloud raw socket signal=" + cloudPath.rawSocketSignal());
            details.add("Cloud message received=" + cloudPath.messageReceived());
            details.add("Cloud eventlog received=" + cloudPath.eventLogReceived());
            details.add("Cloud payment-bridge path=" + cloudPath.paymentBridgePath());
            details.add("Cloud outbound broadcast path=" + cloudPath.outboundBroadcastPath());
            details.add("Token fetch start=" + tokenPath.tokenFetchStarted());
            details.add("Token fetch success=" + tokenPath.tokenFetchSuccess());
            details.add("Token connect-after-token=" + tokenPath.connectionStartedAfterToken());
            details.add("Token failure=" + tokenPath.tokenFailure());
            details.add("Token max-retries=" + tokenPath.maxRetriesReached());

            if (!cloudPath.messageReceived()) {
                failures.add("Required cloud signal missing: Cloud Message received");
            }
            if (!cloudPath.eventLogReceived()) {
                failures.add("Required cloud signal missing: POYNT_CLOUD_MESSAGE_RECEIVED");
            }
            if (cloudPath.processingError()) {
                failures.add("Cloud message processing error found in logs");
            }

            boolean connectedAtInject = analyzer.connectedAt(injected.injectedAtEpochMs());
            if (!connectedAtInject) {
                failures.add("Cloud inject happened while socket was not connected");
            }
            if (cloudPath.receivedAtEpochMs() > 0) {
                long receiveLatency = cloudPath.receivedAtEpochMs() - injected.injectedAtEpochMs();
                details.add("Cloud received latency ms=" + receiveLatency);
                if (receiveLatency > 60_000L) {
                    failures.add("Cloud receive latency exceeded 60s while connected: " + receiveLatency + "ms");
                }
            }
        }

        if (!failures.isEmpty()) {
            return ValidationResult.fail("Cloud Messaging Validation", String.join("; ", failures), failures);
        }
        return ValidationResult.pass(
                "Cloud Messaging Validation",
                "Payload generated, injected, delivered, processed, and correlated",
                details,
                Map.of(
                        "correlationId", injected.correlationId(),
                        "deliveryLatencyMs", report.deliveryLatencyMs(),
                        "processingLatencyMs", report.processingLatencyMs()
                )
        );
    }
}
