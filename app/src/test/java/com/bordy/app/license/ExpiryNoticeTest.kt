package com.bordy.app.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpiryNoticeTest {

    private val day = 86_400_000L
    private val hour = 3_600_000L
    private val minute = 60_000L

    @Test
    fun `days round up so a partial day is never shown as zero`() {
        assertEquals("يوم واحد", formatNoticeRemaining(day / 2, NoticeUnit.DAYS))
        assertEquals("يومان", formatNoticeRemaining(2 * day, NoticeUnit.DAYS))
        assertEquals("3 أيام", formatNoticeRemaining(2 * day + 1, NoticeUnit.DAYS))
        assertEquals("11 يوماً", formatNoticeRemaining(11 * day, NoticeUnit.DAYS))
    }

    @Test
    fun `hours use the correct arabic form`() {
        assertEquals("ساعة واحدة", formatNoticeRemaining(hour, NoticeUnit.HOURS))
        assertEquals("ساعتان", formatNoticeRemaining(2 * hour, NoticeUnit.HOURS))
        assertEquals("5 ساعات", formatNoticeRemaining(5 * hour, NoticeUnit.HOURS))
        assertEquals("24 ساعة", formatNoticeRemaining(24 * hour, NoticeUnit.HOURS))
    }

    @Test
    fun `minutes round up to the next minute`() {
        assertEquals("دقيقة واحدة", formatNoticeRemaining(30_000L, NoticeUnit.MINUTES))
        assertEquals("دقيقتان", formatNoticeRemaining(minute + 1, NoticeUnit.MINUTES))
        assertEquals("61 دقيقة", formatNoticeRemaining(61 * minute, NoticeUnit.MINUTES))
    }

    @Test
    fun `expired windows never claim a full unit`() {
        assertEquals("أقل من يوم", formatNoticeRemaining(0L, NoticeUnit.DAYS))
        assertEquals("أقل من ساعة", formatNoticeRemaining(-5L, NoticeUnit.HOURS))
        assertEquals("أقل من دقيقة", formatNoticeRemaining(-5L, NoticeUnit.MINUTES))
    }

    @Test
    fun `threshold follows the unit chosen in the panel`() {
        assertEquals(3 * day, ExpiryNoticeConfig(unit = NoticeUnit.DAYS, value = 3).thresholdMs)
        assertEquals(6 * hour, ExpiryNoticeConfig(unit = NoticeUnit.HOURS, value = 6).thresholdMs)
        assertEquals(45 * minute, ExpiryNoticeConfig(unit = NoticeUnit.MINUTES, value = 45).thresholdMs)
    }

    @Test
    fun `unknown units fall back to days`() {
        assertEquals(NoticeUnit.DAYS, NoticeUnit.fromKey(null))
        assertEquals(NoticeUnit.DAYS, NoticeUnit.fromKey("weeks"))
        assertEquals(NoticeUnit.MINUTES, NoticeUnit.fromKey("minutes"))
    }

    @Test
    fun `reminder is on unless the owner turns it off`() {
        assertTrue(ExpiryNoticeConfig().enabled)
        assertFalse(ExpiryNoticeConfig(enabled = false).enabled)
    }

    @Test
    fun `plain remaining text handles sub-day values`() {
        assertEquals("يومان", formatRemaining(2 * day))
        assertEquals("3 ساعات", formatRemaining(3 * hour))
        assertEquals("25 دقيقة", formatRemaining(25 * minute))
        assertEquals("أقل من دقيقة", formatRemaining(20_000L))
        assertEquals("أقل من دقيقة", formatRemaining(0L))
    }

    @Test
    fun `a fresh seven day trial is not under-reported as six days`() {
        assertEquals("6 أيام و23 ساعة", formatRemaining(7 * day - 5 * minute))
        assertEquals("يومان و3 ساعات", formatRemaining(2 * day + 3 * hour))
        assertEquals("يوم واحد و5 ساعات", formatRemaining(day + 5 * hour))
        assertEquals("7 أيام", formatRemaining(7 * day))
    }
}
