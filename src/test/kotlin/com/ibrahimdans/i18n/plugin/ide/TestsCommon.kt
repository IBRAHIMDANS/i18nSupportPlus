package com.ibrahimdans.i18n.plugin.ide

import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.extensions.PluginId
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.junit.jupiter.api.Assumptions.assumeTrue

internal fun CodeInsightTestFixture.runWithConfig (config: Config, block: () -> Unit) {
    val settings = Settings.getInstance(this.project)
    val original = settings.config()
    settings.setConfig(config)
    try {
        block()
    } finally {
        settings.setConfig(original)
    }
}

/**
 * Launches [action] and waits for the work it hands off to finish before returning.
 *
 * Key creation looks its translation files up in a non-blocking read action, off the EDT, then
 * writes from `finishOnUiThread`. `launchAction` alone returns before that write, so a
 * `checkResult` right after it would compare the file as it was.
 *
 * Each hop is an EDT event followed by a background lookup, and extraction chains them:
 * `KeyCreator` looks up whether a file exists, then `CreateKeyQuickFix` looks up where to write.
 * Every round flushes the EDT queue and waits for the lookups it started; a round with nothing
 * pending costs nothing, so [ASYNC_ROUNDS] leaves headroom above the longest chain.
 */
internal fun CodeInsightTestFixture.launchActionAndWait(action: IntentionAction) {
    launchAction(action)
    repeat(ASYNC_ROUNDS) {
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
    }
    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
}

private const val ASYNC_ROUNDS = 4

/** The Vue plugin, which the plugin depends on optionally (`vueConfig.xml`). */
private val VUE_PLUGIN = PluginId.getId("org.jetbrains.plugins.vue")

/**
 * Runs [block] only when the Vue plugin is loaded, and skips the test otherwise.
 *
 * Vue support is an optional dependency: `plugin.xml` declares it with `optional="true"`, so a
 * `.vue` file is plain text in an IDE without it and every assertion below would fail for a
 * reason that is not a regression. `prepareTestSandbox` goes out of its way to install the
 * plugin — flattening its `lib/modules/` layout — which is exactly the kind of arrangement that
 * breaks quietly. Skipping says so; failing would blame the plugin.
 */
internal fun runVue(block: () -> Unit) {
    val vue = PluginManagerCore.getPlugin(VUE_PLUGIN)
    assumeTrue(vue != null && vue.isEnabled, "the Vue plugin is not loaded in this sandbox")
    block()
}

/**
 * The PSI element carrying [text] in [file], reaching into an injected fragment when the host
 * PSI has none of its own.
 *
 * A `{{ }}` interpolation in a Vue template is an `XmlText` holding an injected JS file. The
 * editor resolves references inside that injected file, so a test reading only the host PSI
 * sees zero references and would conclude the feature is broken when it is not.
 */
internal fun CodeInsightTestFixture.elementAt(file: PsiFile, text: String): PsiElement? {
    val offset = file.text.indexOf(text)
    if (offset < 0) return null

    val host = file.findElementAt(offset)?.parent
    if (host?.references?.isNotEmpty() == true) return host

    val injected = InjectedLanguageUtil.findInjectedPsiNoCommit(file, offset) ?: return host
    val injectedOffset = injected.text.indexOf(text)
    if (injectedOffset < 0) return host
    return injected.findElementAt(injectedOffset)?.parent
}
