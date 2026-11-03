package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.intellij.ui.JBColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import javax.swing.UIManager

/**
 * The tints of a missing and of a blank cell when the theme does not define `FileColor.Rose` /
 * `FileColor.Yellow` — classic Darcula defines neither. The dark side must stay dark: a light
 * pink behind the theme's light text is what made the cells unreadable.
 */
class TableCellBackgroundTest {

    private fun luminance(color: Color): Double =
        (0.2126 * color.red + 0.7152 * color.green + 0.0722 * color.blue) / 255

    @Test
    fun `the fallbacks are dark on a dark theme`() {
        listOf(MISSING_BACKGROUND_FALLBACK, BLANK_BACKGROUND_FALLBACK).forEach { fallback ->
            assertTrue(luminance(fallback.darkVariant) < 0.3, "dark variant of $fallback is dark")
        }
    }

    @Test
    fun `the fallbacks are light on a light theme, and differ from their dark side`() {
        assumeTrue(JBColor.isBright(), "the test look and feel is a light one")
        listOf(MISSING_BACKGROUND_FALLBACK, BLANK_BACKGROUND_FALLBACK).forEach { fallback ->
            val light = Color(fallback.rgb)
            assertTrue(luminance(light) > 0.8, "light variant of $fallback is light")
            assertNotEquals(light.rgb, fallback.darkVariant.rgb, "the two sides of $fallback differ")
        }
    }

    @Test
    fun `a theme that defines the file colours keeps them`() {
        val rose = Color(0x123456)
        val yellow = Color(0x654321)
        val previousRose = UIManager.get("FileColor.Rose")
        val previousYellow = UIManager.get("FileColor.Yellow")
        try {
            UIManager.put("FileColor.Rose", rose)
            UIManager.put("FileColor.Yellow", yellow)
            assertEquals(rose.rgb, MISSING_BACKGROUND.rgb)
            assertEquals(yellow.rgb, BLANK_BACKGROUND.rgb)
        } finally {
            UIManager.put("FileColor.Rose", previousRose)
            UIManager.put("FileColor.Yellow", previousYellow)
        }
    }
}
