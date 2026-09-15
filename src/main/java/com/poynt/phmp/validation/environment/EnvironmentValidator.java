package com.poynt.phmp.validation.environment;

import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.device.DeviceClient;
import com.poynt.phmp.logs.ScenarioLogAnalyzer;
import com.poynt.phmp.model.ValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class EnvironmentValidator {

    private final PhmpConfig config;
    private final DeviceClient device;

    public EnvironmentValidator(PhmpConfig config, DeviceClient device) {
        this.config = config;
        this.device = device;
    }

    public ValidationResult validate() {
        PhmpConfig.RegionConfig region = config.region(device.region());
        List<String> details = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        String endpoint = device.selectedEndpoint();
        String discovery = device.selectedDiscovery();

        // Compared by host, not by string: PCM dials wss://host/streams/{biz}/{store}/{device}?token=...,
        // so the configured value and the observed value legitimately differ in path and trailing slash.
        String expectedHost = ScenarioLogAnalyzer.hostOf(region.websocket().endpoint);
        String actualHost = ScenarioLogAnalyzer.hostOf(endpoint);
        if (!expectedHost.equalsIgnoreCase(actualHost)) {
            failures.add("Incorrect endpoint host for " + region.code()
                    + ". expected=" + expectedHost + " actual=" + actualHost);
        } else {
            details.add(region.code() + " endpoint host correct: " + actualHost);
        }

        if (!region.websocket().discovery.equals(discovery)) {
            failures.add("Incorrect discovery for " + region.code()
                    + ". expected=" + region.websocket().discovery + " actual=" + discovery);
        } else {
            details.add(region.code() + " discovery correct: " + discovery);
        }

        if (!config.isSimulate()) {
            ScenarioLogAnalyzer analyzer = device.logAnalyzer();
            String discoveredHost = ScenarioLogAnalyzer.hostOf(analyzer.latestDiscoveredHost().orElse(""));
            if (discoveredHost.isBlank()) {
                failures.add("Could not resolve the discovered PCM wss host from logs");
            } else if (!expectedHost.equalsIgnoreCase(discoveredHost)) {
                failures.add("Resolved host mismatch for " + region.code()
                        + ". expectedHost=" + expectedHost + " discoveredHost=" + discoveredHost);
            } else {
                details.add("Discovered PCM host matches region allowlist: " + discoveredHost);
            }

            // Every host PCM dialled has to be the region's host. When PCM cannot reach discovery it
            // falls back to a built-in default (observed: wss://pcm.poynt.net, production), and a dev
            // terminal reaching for production must never be reported as a correct environment.
            for (String dialed : analyzer.dialedHosts()) {
                String dialedHost = ScenarioLogAnalyzer.hostOf(dialed);
                if (!expectedHost.equalsIgnoreCase(dialedHost)) {
                    failures.add("PCM dialled a host outside the " + region.code() + " allowlist: "
                            + dialedHost + " (expected " + expectedHost + ")");
                }
            }

            // A stale seed property is worth surfacing after an environment switch, but it is not a
            // failure on its own: PCM ignores it once discovery answers.
            String seed = device.shell("getprop persist.poynt.srvc.url.pcm").trim();
            if (!seed.isBlank() && !ScenarioLogAnalyzer.hostOf(seed).equalsIgnoreCase(discoveredHost)) {
                details.add("Note: persist.poynt.srvc.url.pcm still seeds " + seed
                        + " while discovery resolves " + discoveredHost);
            }
        }

        if (!failures.isEmpty()) {
            return ValidationResult.fail("Environment Validation", String.join("; ", failures), failures);
        }
        return ValidationResult.pass(
                "Environment Validation",
                region.code() + " endpoint and discovery validated",
                details,
                Map.of("region", region.code(), "endpoint", endpoint, "discovery", discovery)
        );
    }
}
