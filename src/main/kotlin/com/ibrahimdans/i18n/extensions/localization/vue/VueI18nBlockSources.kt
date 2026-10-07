package com.ibrahimdans.i18n.extensions.localization.vue

import com.ibrahimdans.i18n.ComponentSourceProvider
import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.extensions.localization.json.JsonElementTree
import com.ibrahimdans.i18n.extensions.localization.json.JsonLocalization
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

/**
 * The messages of a Vue single-file component's `<i18n>` block, one source per locale:
 * `<i18n>{ "en": { "hello": "Hi" }, "fr": { "hello": "Salut" } }</i18n>` gives an `en` and an `fr`
 * source, holding `hello`, for the keys written in that component only.
 *
 * The Vue plugin already injects JSON into the block (`<i18n>`, `<i18n lang="json">`), so each
 * locale's object is read as the JSON translation files are ([JsonElementTree]); nothing is parsed
 * here. A YAML block (`lang="yaml"`) is not read yet. Recomputed on each call: a component holds
 * one block, and a cached value would hold trees the platform cannot compare.
 */
class VueI18nBlockSources : ComponentSourceProvider {

    override fun sourcesFor(caller: PsiElement): List<LocalizationSource> {
        val file = InjectedLanguageManager.getInstance(caller.project).getTopLevelFile(caller) ?: return emptyList()
        if (!file.name.endsWith(VUE_EXTENSION)) return emptyList()
        val localization = Extensions.LOCALIZATION.extensionList.firstOrNull { it is JsonLocalization } ?: return emptyList()
        val namespace = Settings.getInstance(caller.project).config().defaultNamespaces().first()
        val path = file.virtualFile?.path ?: file.name
        return PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .filter { it.name == BLOCK && it.parentTag == null }
            .flatMap { block -> localesOf(block) }
            .map { (locale, messages) ->
                LocalizationSource(
                    JsonElementTree(messages), namespace, locale, "$path#$BLOCK/$locale", localization,
                    locale = locale, namespace = namespace
                )
            }
    }

    /** Each locale of [block] with its messages, as the injected JSON holds them. */
    private fun localesOf(block: XmlTag): List<Pair<String, JsonObject>> {
        val manager = InjectedLanguageManager.getInstance(block.project)
        val roots = mutableListOf<JsonObject>()
        for (host in PsiTreeUtil.findChildrenOfType(block, PsiLanguageInjectionHost::class.java)) {
            manager.getInjectedPsiFiles(host)?.forEach { injected ->
                ((injected.first as? JsonFile)?.topLevelValue as? JsonObject)?.let { roots += it }
            }
        }
        return roots.flatMap { root ->
            root.propertyList.mapNotNull { locale -> (locale.value as? JsonObject)?.let { locale.name to it } }
        }
    }

    private companion object {
        const val BLOCK = "i18n"
        const val VUE_EXTENSION = ".vue"
    }
}
