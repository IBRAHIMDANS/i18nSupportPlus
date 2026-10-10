package com.ibrahimdans.i18n.plugin.translate

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import java.util.UUID

/**
 * One engine as the project stores it: an [EngineConfig] with mutable fields and a no-arg
 * constructor for XML serialization, its headers one `Name: value` per line. [id] is unique per
 * entry — two OpenAI-compatible engines can coexist — and names its API key in [ApiKeyStore].
 * The key itself is never stored here: this file lives in `.idea/`, often versioned.
 */
data class EngineState(
    var id: String = "",
    var preset: String = "",
    var name: String = "",
    var url: String = "",
    var headers: String = "",
    var body: String = "",
    var responsePath: String = "",
    var unescapeHtml: Boolean = false,
    var local: Boolean = false
) {
    constructor() : this("", "", "", "", "", "", "", false, false)

    fun toConfig(): EngineConfig = EngineConfig(
        id = id, name = name, url = url.trim(),
        headers = headers.lines().mapNotNull { line ->
            line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() }
        }.toMap(),
        body = body, responsePath = responsePath.trim(), unescapeHtml = unescapeHtml, local = local,
        // The batch form is the preset's, valid while the entry still sends the preset's request:
        // a changed URL (DeepL Pro) keeps it, a rewritten body does not.
        batch = TranslationEngines.preset(preset)?.takeIf { it.body == body && it.responsePath == responsePath.trim() }?.batch
    )

    companion object {
        /** A new entry filled from [preset], with an id of its own. */
        fun of(preset: EngineConfig): EngineState = EngineState(
            id = UUID.randomUUID().toString(), preset = preset.id, name = preset.name, url = preset.url,
            headers = preset.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" },
            body = preset.body, responsePath = preset.responsePath, unescapeHtml = preset.unescapeHtml, local = preset.local
        )
    }
}

/**
 * Machine translation for one project. Off by default: the text of the translations leaves for the
 * engine chosen, so each project opts in on its own (TASK-Q decision). Kept apart from `Settings` /
 * `Config`, in its own file of `.idea/`.
 */
@Service(Service.Level.PROJECT)
@State(name = "i18nMachineTranslation", storages = [Storage("i18nMachineTranslation.xml")])
class MachineTranslationSettings : PersistentStateComponent<MachineTranslationSettings.Settings> {

    data class Settings(
        var enabled: Boolean = false,
        var engines: MutableList<EngineState> = mutableListOf()
    )

    private var state = Settings()

    override fun getState(): Settings = state

    override fun loadState(loaded: Settings) {
        state = Settings()
        XmlSerializerUtil.copyBean(loaded, state)
    }

    /** A detached copy, edited by the settings page until *Apply*. */
    fun copy(): Settings = Settings(state.enabled, state.engines.map { it.copy() }.toMutableList())

    /**
     * The engines as one provider, tried in order — null when the project has not opted in or
     * configured none, which callers read as "no machine translation here".
     */
    fun provider(keys: ApiKeyStore = PasswordSafeApiKeys, transport: HttpTransport = PlatformHttpTransport): TranslationProvider? {
        if (!state.enabled || state.engines.isEmpty()) return null
        return TranslationEngines.provider(state.engines.map { it.toConfig() }, { keys.get(it.id) }, transport)
    }

    companion object {
        fun getInstance(project: Project): MachineTranslationSettings = project.getService(MachineTranslationSettings::class.java)
    }
}

/** Where engine API keys live, apart from the project files. */
interface ApiKeyStore {
    fun get(engineId: String): String
    fun set(engineId: String, key: String)
}

/** The IDE's password safe: the OS keychain or KeePass, as the user configured it. */
object PasswordSafeApiKeys : ApiKeyStore {

    private fun attributes(engineId: String) =
        CredentialAttributes(generateServiceName("i18n Support Plus", "translate.$engineId"))

    override fun get(engineId: String): String = PasswordSafe.instance.getPassword(attributes(engineId)).orEmpty()

    override fun set(engineId: String, key: String) =
        PasswordSafe.instance.setPassword(attributes(engineId), key.ifEmpty { null })
}
