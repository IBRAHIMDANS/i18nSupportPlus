package com.ibrahimdans.i18n.plugin.ide.settings

/**
 * Configuration for a single i18n module.
 * Must have a no-arg constructor and mutable fields for IntelliJ XML serialization.
 *
 * [referenceLocale] is the locale this module translates *from*. It defaults to the empty
 * string, which means "not decided here": callers fall back to the global preview locale,
 * then to the folding language. A state persisted before the field existed simply carries
 * no `referenceLocale` option, and XML deserialization leaves that default in place — so an
 * older workspace keeps behaving exactly as it did.
 *
 * [callTemplate] is the call written at extraction, `translate({key})`, where `{key}` stands for the
 * call's arguments. Empty, the default, keeps the call of the module's framework. It only writes the
 * call: reading the wrapper back as a translation call is the job of the *Key assistance rules*.
 */
data class ModuleConfig(
    var name: String = "",
    var pathTemplate: String = "",
    var fileTemplate: String = "",
    var keyTemplate: String = "",
    var rootDirectory: String = "",
    var preset: String = "",
    var referenceLocale: String = "",
    var callTemplate: String = ""
) {
    // Explicit no-arg constructor required by XmlSerializerUtil for List<T> serialization
    constructor() : this("", "", "", "", "", "", "", "")
}
