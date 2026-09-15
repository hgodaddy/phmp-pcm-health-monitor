package com.poynt.phmp.suite;

import com.poynt.phmp.engine.PcmHealthEngine;
import com.poynt.phmp.model.ExecutionSummary;
import com.poynt.phmp.support.PhmpTestSupport;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Epic("PHMP Phase 1 MVP")
@Feature("EU Lab Validation")
@Tag("eu")
@Tag("nightly")
class PcmLifecycleEuTest {

    @Test
    @Story("Complete PCM communication lifecycle - EU")
    @DisplayName("EU leg: flash/OTA → install → startup → auth → websocket → health → reconnect → cloud → IoT → gate")
    @Description("Validates the full Phase 1 PCM lifecycle on the EU lab profile (simulate by default).")
    void euLifecyclePasses() {
        ExecutionSummary summary = PhmpTestSupport.runAndAssert("EU");
        org.junit.jupiter.api.Assertions.assertEquals(PcmHealthEngine.PHASE1_STEP_COUNT, summary.getResults().size());
        org.junit.jupiter.api.Assertions.assertEquals("EU", summary.getRegion());
    }
}
