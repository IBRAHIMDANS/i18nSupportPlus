package com.ibrahimdans.i18n.extensions.localization.vue

import com.ibrahimdans.i18n.ComponentSourceProvider
import com.ibrahimdans.i18n.Extensions
import com.ibrahimdans.i18n.Localization
import com.ibrahimdans.i18n.LocalizationSource
import com.ibrahimdans.i18n.extensions.localization.json.JsonElementTree
import com.ibrahimdans.i18n.extensions.localization.json.JsonLocalization
import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

/**
 * The messages of a Vue single-file component's `<i18n>` block, one source per locale:
 * `<i18n>{ "en": { "hello": "Hi" }, "fr": { "hello": "Salut" } }</i18n>` gives an `en` and an `fr`
 * source, holding `hello`, for the keys written in that component only.
 *
 * The Vue plugin already injects the block's language, so each locale is read from the injected
 * file as the translation files of that format are; nothing is parsed here. A subclass reads one
 * format: [VueI18nBlockSources] JSON (`<i18n>`, `<i18n lang="json">`), its YAML counterpart
 * `lang="yaml"` — declared apart, since YAML is an optional dependency of its own. Recomputed on
 * each call: a component holds one block, and a cached value would hold trees the platform cannot
 * compare.
 */
abstract class VueI18nBlockReader : ComponentSourceProvider {

    /** The localization of the format this reader understands, or null when it is not loaded. */
    protected abstract fun localization(): Localization<PsiElement>?

    /** Each locale of the injected block [file] with its messages, when the file is of this format. */
    protected abstract fun localesOf(file: PsiFile): List<Pair<String, Tree<PsiElement>>>

    override fun sourcesFor(caller: PsiElement): List<LocalizationSource> {
        val manager = InjectedLanguageManager.getInstance(caller.project)
        val file = manager.getTopLevelFile(caller) ?: return emptyList()
        if (!file.name.endsWith(VUE_EXTENSION)) return emptyList()
        val localization = localization() ?: return emptyList()
        val namespace = Settings.getInstance(caller.project).config().defaultNamespaces().first()
        val path = file.virtualFile?.path ?: file.name
        return PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .filter { it.name == BLOCK && it.parentTag == null }
            .flatMap { block -> PsiTreeUtil.findChildrenOfType(block, PsiLanguageInjectionHost::class.java) }
            .flatMap { host -> manager.getInjectedPsiFiles(host).orEmpty().mapNotNull { it.first as? PsiFile } }
            .flatMap(::localesOf)
            .map { (locale, messages) ->
                LocalizationSource(
                    messages, namespace, locale, "$path#$BLOCK/$locale", localization,
                    locale = locale, namespace = namespace
                )
            }
    }

    private companion object {
        const val BLOCK = "i18n"
        const val VUE_EXTENSION = ".vue"
    }
}

/** `<i18n>` and `<i18n lang="json">`: the block is JSON, each top-level key a locale. */
class VueI18nBlockSources : VueI18nBlockReader() {

    override fun localization(): Localization<PsiElement>? =
        Extensions.LOCALIZATION.extensionList.firstOrNull { it is JsonLocalization }

    override fun localesOf(file: PsiFile): List<Pair<String, Tree<PsiElement>>> {
        val root = (file as? JsonFile)?.topLevelValue as? JsonObject ?: return emptyList()
        return root.propertyList.mapNotNull { locale ->
            (locale.value as? JsonObject)?.let { locale.name to JsonElementTree(it) }
        }
    }
}
