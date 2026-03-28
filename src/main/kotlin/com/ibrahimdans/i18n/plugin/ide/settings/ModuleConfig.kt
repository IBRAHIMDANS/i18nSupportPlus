package com.ibrahimdans.i18n.plugin.ide.settings

/**
 * Configuration for a single i18n module.
 * Must have a no-arg constructor and mutable fields for IntelliJ XML serialization.
 */
data class ModuleConfig(
    var name: String = "",
    var pathTemplate: String = "",
    var fileTemplate: String = "",
    var keyTemplate: String = "",
    var rootDirectory: String = "",
    var preset: String = ""
) {
    // Explicit no-arg constructor required by XmlSerializerUtil for List<T> serialization
    constructor() : this("", "", "", "", "", "")
}
