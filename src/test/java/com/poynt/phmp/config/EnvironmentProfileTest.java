package com.poynt.phmp.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Environment selection must change the endpoints the run actually uses, so that a report labelled
 * env=dev cannot have been produced against another environment's endpoints.
 */
class EnvironmentProfileTest {

    @AfterEach
    void clearOverride() {
        System.clearProperty("phmp.env");
    }

    @Test
    @DisplayName("env=dev selects the dev PCM endpoint and discovery URL")
    void devProfileApplies() {
        System.setProperty("phmp.env", "dev");
        PhmpConfig config = PhmpConfig.load();

        assertEquals("dev", config.phmp.env);
        assertTrue(config.hasEnvironmentProfile("US"));
        assertEquals("wss://pcm-ci.poynt.net", config.region("US").websocket().endpoint);
        assertEquals("https://fouroneone.poynt.net/discovery/services",
                config.region("US").websocket().discovery);
    }

    @Test
    @DisplayName("env=ote selects the OTE endpoint")
    void oteProfileApplies() {
        System.setProperty("phmp.env", "ote");
        PhmpConfig config = PhmpConfig.load();
        assertEquals("wss://pcm-ote.poynt.net", config.region("US").websocket().endpoint);
    }

    @Test
    @DisplayName("An env with no profile falls back to the default endpoints")
    void unknownEnvFallsBack() {
        System.setProperty("phmp.env", "local");
        PhmpConfig config = PhmpConfig.load();
        assertFalse(config.hasEnvironmentProfile("US"));
        assertEquals("wss://pcm-us.lab.poynt.net/v1/socket", config.region("US").websocket().endpoint);
    }
}
