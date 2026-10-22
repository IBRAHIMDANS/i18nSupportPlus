package com.ibrahimdans.i18n.plugin.ide.settings

/** Where a [Config] is read from and written back to: the project settings, or memory in tests. */
interface ConfigStore {
    fun config(): Config
    fun setConfig(config: Config)
}
