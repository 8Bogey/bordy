package com.bordy.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateConfigTest {

    @Test
    fun compareVersions_numericPerSegment() {
        assertEquals(0, compareVersions("1.0", "1"))
        assertTrue(compareVersions("1.9", "1.10") < 0)
        assertTrue(compareVersions("2.0", "1.9") > 0)
        assertTrue(compareVersions("1.0.1", "1.0") > 0)
        assertEquals(0, compareVersions("1.0", "1.0.0"))
    }

    @Test
    fun updateRequired_onlyBelowMinimum() {
        val config = UpdateConfig(minVersion = "1.1", latestVersion = "1.2")
        assertTrue(config.updateRequired("1.0"))
        assertFalse(config.updateRequired("1.1"))
        assertFalse(config.updateRequired("2.0"))
    }

    @Test
    fun rollbackRequired_onlyAboveLatest() {
        val config = UpdateConfig(minVersion = "1.0", latestVersion = "1.0")
        assertTrue(config.rollbackRequired("1.1"))
        assertFalse(config.rollbackRequired("1.0"))
        assertFalse(config.rollbackRequired("0.9"))
    }

    @Test
    fun trigger_onlyForDevicesBehindLatest() {
        val config = UpdateConfig(
            minVersion = "1.0",
            latestVersion = "1.2",
            triggerMode = UpdateConfig.TRIGGER_OPTIONAL,
            triggerId = 1730000000000L,
        )
        assertTrue(config.behindLatest("1.1"))
        assertFalse(config.behindLatest("1.2"))
        assertFalse(config.behindLatest("1.3"))
        assertTrue(config.triggerActive())
        assertFalse(config.triggerForced())
    }

    @Test
    fun trigger_offIsInactiveEvenWithAnId() {
        val config = UpdateConfig(latestVersion = "2.0", triggerMode = UpdateConfig.TRIGGER_OFF, triggerId = 5L)
        assertFalse(config.triggerActive())
        assertFalse(config.triggerForced())
        assertTrue(config.behindLatest("1.0"))
    }

    @Test
    fun trigger_forcedIsRecognised() {
        val config = UpdateConfig(latestVersion = "2.0", triggerMode = UpdateConfig.TRIGGER_FORCED)
        assertTrue(config.triggerActive())
        assertTrue(config.triggerForced())
    }

    @Test
    fun silentPrefetch_widerThanPromptSoTheUserNeverWaits() {
        val config = UpdateConfig(minVersion = "1.0", latestVersion = "1.3", triggerMode = UpdateConfig.TRIGGER_OFF)
        // متأخر عن أحدث إصدار = ملفه يُنزَّل في الخفاء ولو بلا أي تنبيه
        assertTrue(config.prefetchNeeded("1.2"))
        // محدَّث: لا تنزيل ولا استهلاك بيانات
        assertFalse(config.prefetchNeeded("1.3"))
        // أرجعته الإدارة لنسخة أقدم = يلزم تنزيل ملفها أيضاً
        assertTrue(config.prefetchNeeded("1.4"))
    }

    @Test
    fun forcedTriggerDoesNotChangePrefetchRules() {
        val forced = UpdateConfig(latestVersion = "1.3", triggerMode = UpdateConfig.TRIGGER_FORCED)
        assertTrue(forced.prefetchNeeded("1.2"))
        assertFalse(forced.prefetchNeeded("1.3"))
    }
}
