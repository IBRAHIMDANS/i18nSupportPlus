package com.ibrahimdans.i18n.plugin.ide.settings.rules

/**
 * Represents a single key assistance rule with its constraint.
 * Must have a no-arg constructor for IntelliJ XML serialization.
 */
data class EditorRuleState(
    var id: String = "",
    var language: String = "",
    var trigger: String = "",
    var priority: Int = 0,
    var exclude: Boolean = false,
    var constraintType: String = "",
    var value: String = "",
    var matchMode: String = "",
    var negated: Boolean = false
) {
    // Explicit no-arg constructor required by XmlSerializerUtil for List<T> serialization
    constructor() : this("", "", "", 0, false, "", "", "", false)
}
