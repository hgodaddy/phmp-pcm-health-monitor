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
@Feature("US Lab Validation")
@Tag("us")
@Tag("nightly")
class PcmLifecycleUsTest {

    @Test
    @Story("Complete PCM communication lifecycle - US")
    @DisplayName("US leg: flash/OTA → install → startup → auth → websocket → health → reconnect → cloud → IoT → gate")
    @Description("Validates the full Phase 1 PCM lifecycle on the US lab profile (simulate by default).")
    void usLifecyclePasses() {
        ExecutionSummary summary = PhmpTestSupport.runAndAssert("US");
        org.junit.jupiter.api.Assertions.assertEquals(PcmHealthEngine.PHASE1_STEP_COUNT, summary.getResults().size());
        org.junit.jupiter.api.Assertions.assertEquals("US", summary.getRegion());
    }
}
