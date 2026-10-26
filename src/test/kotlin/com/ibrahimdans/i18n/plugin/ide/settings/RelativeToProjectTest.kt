package com.ibrahimdans.i18n.plugin.ide.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RelativeToProjectTest {

    @Test
    fun `a file under the project is shown relative to it`() {
        assertEquals(
            "public/locales/en/common.json",
            relativeToProject("/home/me/app/public/locales/en/common.json", "/home/me/app")
        )
    }

    @Test
    fun `a trailing slash on the project directory changes nothing`() {
        assertEquals("locales/en.json", relativeToProject("/home/me/app/locales/en.json", "/home/me/app/"))
    }

    @Test
    fun `a file outside the project keeps its full path`() {
        assertEquals("/srv/shared/en.json", relativeToProject("/srv/shared/en.json", "/home/me/app"))
    }

    @Test
    fun `a sibling directory sharing the prefix is not under the project`() {
        assertEquals("/home/me/app-admin/en.json", relativeToProject("/home/me/app-admin/en.json", "/home/me/app"))
    }

    @Test
    fun `an unknown project directory keeps the full path`() {
        assertEquals("/home/me/app/en.json", relativeToProject("/home/me/app/en.json", null))
    }

    @Test
    fun `a Windows path under the project is shown relative to it`() {
        assertEquals("locales/en.json", relativeToProject("C:\\work\\app\\locales\\en.json", "C:/work/app"))
    }
}
