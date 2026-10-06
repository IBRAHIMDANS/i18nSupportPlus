package com.ibrahimdans.i18n.extensions.lang.js

/**
 * What the hardcoded-text inspections agree is text to translate, whatever the template syntax:
 * [HardcodedJsxTextInspection] for JSX, [HardcodedVueTextInspection] for Vue templates.
 */
internal object HardcodedTextRules {

    /** The attributes whose value is shown to the user; any other one is code. */
    val VISIBLE_ATTRIBUTES = setOf("title", "placeholder", "alt", "aria-label")

    /** Tags whose text is code rather than a message. */
    val CODE_TAGS = setOf("code", "kbd", "pre", "script", "style")

    private val ENTITY = Regex("&#?\\w+;")

    /** `{{name}}`: a variable's placeholder, whose letters are not text. */
    private val PLACEHOLDER = Regex("\\{\\{\\w+}}")

    /** True when [text] holds a letter once its HTML entities and placeholders are removed. */
    fun isTranslatable(text: String): Boolean =
        text.replace(ENTITY, "").replace(PLACEHOLDER, "").any { it.isLetter() }
}
