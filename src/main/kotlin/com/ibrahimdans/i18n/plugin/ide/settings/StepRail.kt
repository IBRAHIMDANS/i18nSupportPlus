package com.ibrahimdans.i18n.plugin.ide.settings

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * The step rail: every step named at once, the current one picked out.
 *
 * "Step 2 of 3" told the user where they were and nothing about where they were going.
 */
internal class StepRail(private val titles: List<String>) : JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)) {

    private val labels = titles.mapIndexed { index, title ->
        JBLabel("${index + 1}. $title").apply { border = JBUI.Borders.empty(8, 12) }
    }

    init {
        border = JBUI.Borders.emptyLeft(4)
        labels.forEach { add(it) }
        select(0)
    }

    fun select(index: Int) {
        labels.forEachIndexed { position, label ->
            label.foreground = if (position == index) ACTIVE else INACTIVE
            label.font = label.font.deriveFont(if (position == index) 1 else 0)
        }
    }

    private companion object {
        // From the IDE scheme rather than hand-picked RGB, as the stats bar does since #209.
        val ACTIVE = JBColor.namedColor("Label.foreground", 0x000000, 0xBBBBBB)
        val INACTIVE = JBColor.namedColor("Label.disabledForeground", 0x8C8C8C, 0x777777)
    }
}
