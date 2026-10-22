package com.ibrahimdans.i18n.plugin.ide.toolwindow

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.utils.CoreMessages

/**
 * What the namespace combo filters on.
 *
 * The identity of an entry is the type, never the text shown for it. Both used to be the same
 * `String`: [All] was the translated label `toolwindow.table.namespace.all`, compared with `==`
 * to decide whether to filter at all, and [Default] was the literal `"(default)"` — displayed
 * untranslated in a tool window #177 had otherwise localized, and impossible to translate without
 * breaking the comparison that read it back. A project owning a namespace named like either label
 * hit the same collision from the other side.
 */
sealed interface NamespaceFilter {

    /** The text shown in the combo. Never compared against anything. */
    val label: String

    /**
     * The text shown for this filter under [config]. Only [Default] cares: its keys are the
     * ones the loader stripped of their prefix because it belongs to a default namespace, so
     * when the configuration names exactly one, the group can say which — `common (default)`
     * rather than a bare `(default)` a reader looking for `common` never matched with it.
     */
    fun label(config: Config): String = label

    /** Every namespace, i.e. no filtering. */
    data object All : NamespaceFilter {
        override val label: String get() = CoreMessages.message("toolwindow.table.namespace.all")
    }

    /** Keys carrying no namespace prefix at all. */
    data object Default : NamespaceFilter {
        override val label: String get() = CoreMessages.message("toolwindow.table.namespace.default")

        override fun label(config: Config): String =
            config.defaultNamespaces().singleOrNull()
                ?.let { CoreMessages.message("toolwindow.table.namespace.default.named", it) }
                ?: label
    }

    /** One named namespace, the part of a key before its `:`. */
    data class Named(val name: String) : NamespaceFilter {
        override val label: String get() = name
    }
}
