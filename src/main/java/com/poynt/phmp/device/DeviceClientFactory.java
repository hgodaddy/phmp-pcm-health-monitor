package com.poynt.phmp.device;

import com.poynt.phmp.config.PhmpConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DeviceClientFactory {

    private static final Logger log = LoggerFactory.getLogger(DeviceClientFactory.class);

    private DeviceClientFactory() {
    }

    public static DeviceClient create(PhmpConfig config, String region) {
        PhmpConfig.RegionConfig regionConfig = config.region(region);
        if (config.isSimulate()) {
            log.info("Creating simulated device client for region={} serial={}",
                    region, regionConfig.device().serial);
            return new SimulatedDeviceClient(config, regionConfig);
        }
        log.info("Creating ADB device client for region={} serial={}",
                region, regionConfig.device().serial);
        return new AdbDeviceClient(config, regionConfig);
    }
}
