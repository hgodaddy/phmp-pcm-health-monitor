package com.poynt.phmp.suite;

import com.poynt.phmp.config.PhmpConfig;
import com.poynt.phmp.support.PhmpTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ConfigSmokeTest {

    @Test
    @DisplayName("Configuration loads US/EU endpoints and package settings")
    void configLoads() {
        PhmpConfig config = PhmpTestSupport.loadConfig();
        assertNotNull(config.phmp.build.packageName);
        assertFalse(config.phmp.build.packageName.isBlank());
        // Asserted against the base websocket block rather than region(), which an active environment
        // profile (phmp.env=dev in a developer's local.properties) legitimately overrides.
        assertEquals("wss://pcm-us.lab.poynt.net/v1/socket", config.phmp.websocket.us.endpoint);
        assertEquals("wss://pcm-eu.lab.poynt.net/v1/socket", config.phmp.websocket.eu.endpoint);
        assertNotNull(config.region("US").websocket().endpoint);
        assertNotNull(config.region("EU").websocket().endpoint);
    }
}
