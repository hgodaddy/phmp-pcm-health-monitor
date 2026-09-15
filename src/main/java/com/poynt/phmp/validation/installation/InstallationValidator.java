package com.poynt.phmp.validation.installation;

import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.device.DeviceClient;
import com.poynt.phmp.logs.ScenarioLogAnalyzer;
import com.poynt.phmp.model.ValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InstallationValidator {

    private final PhmpConfig config;
    private final DeviceClient device;

    public InstallationValidator(PhmpConfig config, DeviceClient device) {
        this.config = config;
        this.device = device;
    }

    public ValidationResult validate() {
        String packageName = config.phmp.build.packageName;
        List<String> details = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        if (!config.isSimulate()) {
            ScenarioLogAnalyzer analyzer = device.logAnalyzer();
            if (analyzer.hasInstallCertificateFailure()) {
                failures.add("OS install gate failed: certificate/signing mismatch for PoyntCloudMessaging");
            }
            if (analyzer.hasInstallAbiFailure()) {
                failures.add("OS install gate failed: instruction set mismatch for cloudmessaging ABI");
            }
            if (!failures.isEmpty()) {
                return ValidationResult.fail("Installation Validation", String.join("; ", failures), failures);
            }
            details.add("OS install-gate log scan clean (no cert/ABI fatal patterns)");
        }

        DeviceClient.PackageInfo info = device.getPackageInfo(packageName);
        if (!info.installed()) {
            failures.add("APK not installed: " + packageName);
        } else {
            details.add("Installed version=" + info.versionName() + " code=" + info.versionCode());
        }

        if (!device.isPrivilegedApp(packageName)) {
            failures.add("Privileged application flag missing");
        } else {
            details.add("Privileged application confirmed");
        }

        if (!device.hasValidCertificate(packageName)) {
            failures.add("Certificate validation failed");
        } else {
            details.add("Certificate present/valid");
        }

        String fingerprint = device.certificateFingerprint(packageName);
        details.add("Certificate fingerprint=" + fingerprint);
        if (config.isSimulate()
                && (fingerprint == null || !fingerprint.equals(config.phmp.build.expectedCertFingerprint))) {
            failures.add("Certificate fingerprint mismatch. expected="
                    + config.phmp.build.expectedCertFingerprint + " actual=" + fingerprint);
        }

        if (info.installed() && info.versionCode() < config.phmp.build.minVersionCode) {
            failures.add("Version code below minimum. expected>=" + config.phmp.build.minVersionCode
                    + " actual=" + info.versionCode());
        }

        Optional<String> sharedUid = device.getSharedUid(packageName);
        // Matched exactly. The previous contains("system") fallback also accepted the wrapper class name
        // dumpsys prints (SharedUserSetting{...}), which made this check impossible to fail.
        boolean sharedUidOk = sharedUid.isPresent() && config.phmp.build.sharedUid.equals(sharedUid.get());
        if (!sharedUidOk) {
            failures.add("Shared UID mismatch. expected=" + config.phmp.build.sharedUid
                    + " actual=" + sharedUid.orElse("<none>"));
        } else {
            details.add("Shared UID=" + sharedUid.orElse("<none>"));
        }

        List<String> abis = device.getAbis();
        if (!abis.contains(config.phmp.build.expectedAbi)) {
            failures.add("ABI compatibility failed. expected=" + config.phmp.build.expectedAbi + " actual=" + abis);
        } else {
            details.add("ABI list includes " + config.phmp.build.expectedAbi);
        }

        if (!failures.isEmpty()) {
            return ValidationResult.fail("Installation Validation", String.join("; ", failures), failures);
        }
        return ValidationResult.pass(
                "Installation Validation",
                "APK installation, privilege, certificate, shared UID, and ABI checks passed",
                details,
                Map.of("package", packageName, "abis", abis)
        );
    }
}
