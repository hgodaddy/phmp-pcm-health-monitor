package com.poynt.phmp.validation.deployment;

import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.device.DeviceClient;
import com.poynt.phmp.model.ValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Day 1/4 – validates flash and OTA lifecycle readiness after nightly vendor image deploy.
 */
public class FlashOtaValidator {

    private final PhmpConfig config;
    private final DeviceClient device;

    public FlashOtaValidator(PhmpConfig config, DeviceClient device) {
        this.config = config;
        this.device = device;
    }

    public ValidationResult validate() {
        List<String> details = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        DeviceClient.DeploymentState state = device.getDeploymentState();
        details.add("Image channel=" + state.imageChannel());
        details.add("Build fingerprint=" + state.buildFingerprint());
        details.add("Flash verified=" + state.flashVerified());
        details.add("OTA verified=" + state.otaVerified());
        details.add("Boot verified after deploy=" + state.bootVerifiedAfterDeploy());

        if (config.phmp.deployment.flashSupported && !state.flashVerified()) {
            failures.add("Flash verification failed for nightly vendor image");
        }
        if (config.phmp.deployment.otaSupported && !state.otaVerified()) {
            failures.add("OTA verification failed");
        }
        if (config.phmp.deployment.verifyBootAfterDeploy && !state.bootVerifiedAfterDeploy()) {
            failures.add("Boot verification after flash/OTA failed");
        }
        if (!device.isOnline()) {
            failures.add("Device offline after deployment");
        } else {
            details.add("Device online serial=" + device.serial());
        }

        if (!failures.isEmpty()) {
            return ValidationResult.fail("Flash / OTA Deployment Validation", String.join("; ", failures), failures);
        }
        return ValidationResult.pass(
                "Flash / OTA Deployment Validation",
                "Flash/OTA deploy path and post-deploy boot verified",
                details,
                Map.of(
                        "imageChannel", state.imageChannel(),
                        "flashVerified", state.flashVerified(),
                        "otaVerified", state.otaVerified()
                )
        );
    }
}
