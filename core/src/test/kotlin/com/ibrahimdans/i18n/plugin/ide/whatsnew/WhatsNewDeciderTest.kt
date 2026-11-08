package com.ibrahimdans.i18n.plugin.ide.whatsnew

import com.ibrahimdans.i18n.plugin.ide.whatsnew.WhatsNewDecider.Decision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WhatsNewDeciderTest {

    @Test
    fun `a fresh install is not told about changes it never saw`() {
        assertEquals(Decision.RECORD_ONLY, WhatsNewDecider.decide(null, "1.4.0"))
        assertEquals(Decision.RECORD_ONLY, WhatsNewDecider.decide("", "1.4.0"))
    }

    @Test
    fun `an already announced version stays quiet`() {
        assertEquals(Decision.NONE, WhatsNewDecider.decide("1.4.0", "1.4.0"))
    }

    @Test
    fun `a changed version is announced`() {
        assertEquals(Decision.NOTIFY, WhatsNewDecider.decide("1.3.3", "1.4.0"))
    }

    @Test
    fun `a patch of the minor already announced is recorded, not announced`() {
        assertEquals(Decision.RECORD_ONLY, WhatsNewDecider.decide("1.19.0", "1.19.1"))
        assertEquals(Decision.RECORD_ONLY, WhatsNewDecider.decide("1.19.1", "1.19.2"))
    }

    @Test
    fun `a patch of a later minor is announced, with the minor it belongs to`() {
        assertEquals(Decision.NOTIFY, WhatsNewDecider.decide("1.18.1", "1.19.1"))
        assertEquals(Decision.NOTIFY, WhatsNewDecider.decide("1.19.1", "2.0.0"))
        assertEquals(Decision.NOTIFY, WhatsNewDecider.decide("1.19.1", "1.20.0-beta.1"))
    }

    @Test
    fun `a version that does not parse is announced as before`() {
        assertEquals(Decision.NOTIFY, WhatsNewDecider.decide("dev", "1.19.1"))
        assertEquals(Decision.NOTIFY, WhatsNewDecider.decide("1.19.0", "dev"))
    }

    @Test
    fun `a muted user is not announced a new version, which is still recorded`() {
        assertEquals(Decision.RECORD_ONLY, WhatsNewDecider.decide("1.3.3", "1.4.0", muted = true))
        assertEquals(Decision.NONE, WhatsNewDecider.decide("1.4.0", "1.4.0", muted = true))
    }
}
