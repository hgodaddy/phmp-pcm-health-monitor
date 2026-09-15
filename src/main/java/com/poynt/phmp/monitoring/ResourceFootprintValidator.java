package com.poynt.phmp.monitoring;

import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.device.DeviceClient;
import com.poynt.phmp.model.ValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resource health of the PCM process: a socket that stays connected while the process leaks memory
 * still fails in the field, so footprint is scored alongside the connectivity gates.
 *
 * <p>Wired into the lifecycle by a single line in {@link com.poynt.phmp.engine.PcmHealthEngine}.
 */
public class ResourceFootprintValidator {

    public static final String NAME = "Resource Footprint Monitoring";

    /** Headroom over the ~25 MB a healthy PCM process holds on PST3. */
    private static final long MAX_PSS_KB = 196_608;

    private static final Pattern TOTAL_PSS = Pattern.compile("TOTAL:?\\s+(\\d+)");
    private static final long SIMULATED_PSS_KB = 25_600;

    private final PhmpConfig config;
    private final DeviceClient device;

    public ResourceFootprintValidator(PhmpConfig config, DeviceClient device) {
        this.config = config;
        this.device = device;
    }

    public ValidationResult validate() {
        String packageName = config.phmp.build.packageName;
        List<String> details = new ArrayList<>();
        details.add("Package=" + packageName);

        long pssKb = config.isSimulate() ? SIMULATED_PSS_KB : readPssKb(packageName);
        if (pssKb < 0) {
            return ValidationResult.skip(NAME,
                    "Not evaluated: meminfo returned no total for " + packageName,
                    details);
        }

        details.add("Process PSS KB=" + pssKb);
        details.add("Threshold KB=" + MAX_PSS_KB);

        if (pssKb > MAX_PSS_KB) {
            return ValidationResult.fail(NAME,
                    "PCM process footprint above threshold: " + pssKb + "KB > " + MAX_PSS_KB + "KB",
                    details);
        }
        return ValidationResult.pass(NAME,
                "PCM process footprint within threshold",
                details,
                Map.of("processPssKb", pssKb, "thresholdKb", MAX_PSS_KB));
    }

    private long readPssKb(String packageName) {
        Matcher matcher = TOTAL_PSS.matcher(device.shell("dumpsys meminfo " + packageName));
        return matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
    }
}
