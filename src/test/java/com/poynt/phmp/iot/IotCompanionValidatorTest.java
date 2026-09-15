package com.poynt.phmp.iot;

import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.device.DeviceClientFactory;
import com.poynt.phmp.model.ValidationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IotCompanionValidatorTest {

    @Test
    void simulateModePassesWithoutAdb() {
        PhmpConfig config = PhmpConfig.load();
        config.phmp.mode = "simulate";
        ValidationResult result = new IotCompanionValidator(
                config, DeviceClientFactory.create(config, "US")).validate();
        assertTrue(result.isPassed());
        assertFalse(result.isSkipped());
        assertEquals(IotCompanionValidator.CHECK_NAME, result.getName());
    }
}
