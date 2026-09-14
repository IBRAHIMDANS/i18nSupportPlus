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
}
