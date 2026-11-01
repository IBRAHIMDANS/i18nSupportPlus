package com.ibrahimdans.i18n.plugin.utils

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

/**
 * The coroutine scope the plugin's long actions run in, one per project: the platform injects it
 * and cancels it when the project closes, so a sync or a cleanup still running then stops with it.
 *
 * Long work launched here reports through `withBackgroundProgress`, reads through `readAction`
 * and comes back to the EDT through `withContext(Dispatchers.EDT)` — never through a
 * `Task.Backgroundable` whose thread blocks until the EDT has run a write.
 */
@Service(Service.Level.PROJECT)
class PluginCoroutineScope(val scope: CoroutineScope) {

    companion object {
        /** The scope of [project]'s long actions. */
        fun of(project: Project): CoroutineScope = project.service<PluginCoroutineScope>().scope
    }
}
