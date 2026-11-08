package com.ibrahimdans.i18n

/**
 * What a language's plugin reads in a project's code for this one — a technology's configuration,
 * the settings a blank is filled from, what the setup wizard proposes. Registered on
 * `com.ibrahimdans.i18n.codeReader` by the config file of that language's plugin (`jsConfig.xml`
 * for JavaScript): the trunk finds a reader when the plugin is there, and does without it otherwise.
 * Each kind of reader is an interface extending this one, looked up with `filterIsInstance`.
 */
interface CodeReader
