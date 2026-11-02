package com.ibrahimdans.i18n.extensions.localization.properties

import com.ibrahimdans.i18n.plugin.ide.settings.Settings
import com.ibrahimdans.i18n.plugin.tree.Tree
import com.intellij.lang.properties.IProperty
import com.intellij.lang.properties.parsing.PropertiesTokenTypes
import com.intellij.lang.properties.psi.PropertiesFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * A `.properties` file as a tree: its keys are flat (`user.name=…`), and read through the key
 * separator as nested levels (`user` › `name`), as `t('user.name')` resolves them in the other formats.
 *
 * A node is a [path] of the file's keys: the root has none, a leaf is a key the file holds and no
 * longer key extends. The root's value is the file; a level's, the first key below it, where the
 * IDE navigates; a leaf's, the value of its key — the key itself when that value is empty.
 * A key both written and extended (`user=…` beside `user.name=…`) reads as a level.
 */
class PropertiesElementTree private constructor(
    private val file: PsiFile,
    private val separator: String,
    private val path: String?,
) : Tree<PsiElement> {

    private fun keys(): Map<String, IProperty> = keysOf(file)

    private fun below(path: String): Sequence<String> = keys().keys.asSequence().filter { it.startsWith(path + separator) }

    override fun isTree(): Boolean = path == null || (separator.isNotEmpty() && below(path).any())

    override fun value(): PsiElement {
        if (path == null) return file
        if (isTree()) return keys().getValue(below(path).first()).psiElement
        val property = keys().getValue(path)
        return valueOf(property) ?: property.psiElement
    }

    override fun findChild(name: String): Tree<PsiElement>? {
        if (!isTree()) return null
        val child = childPath(name)
        val exists = child in keys() || (separator.isNotEmpty() && below(child).any())
        return if (exists) PropertiesElementTree(file, separator, child) else null
    }

    override fun findChildren(prefix: String): List<Tree<PsiElement>> =
        segments().filter { it.startsWith(prefix) }.map { PropertiesElementTree(file, separator, childPath(it)) }

    override fun entries(): List<Pair<String, Tree<PsiElement>>> =
        segments().map { it to PropertiesElementTree(file, separator, childPath(it)) }

    /** The names of the level's children, in the order the file first writes them. */
    private fun segments(): List<String> {
        if (!isTree()) return emptyList()
        val start = path?.let { it + separator }.orEmpty()
        return keys().keys.asSequence()
            .filter { it.startsWith(start) && it.length > start.length }
            .map { key -> key.substring(start.length).let { rest -> if (separator.isEmpty()) rest else rest.substringBefore(separator) } }
            .distinct()
            .toList()
    }

    private fun childPath(name: String) = if (path == null) name else path + separator + name

    companion object {

        /** The tree of [file], null when it is no `.properties` file. */
        fun create(file: PsiElement): PropertiesElementTree? {
            val psiFile = file as? PsiFile ?: return null
            if (psiFile !is PropertiesFile) return null
            val separator = Settings.getInstance(psiFile.project).config().keySeparator
            return PropertiesElementTree(psiFile, separator, null)
        }

        /** The value element of [property], null when the value is empty and the file writes none. */
        fun valueOf(property: IProperty): PsiElement? =
            property.psiElement.node.findChildByType(PropertiesTokenTypes.VALUE_CHARACTERS)?.psi

        /**
         * Each key of [file], unescaped, with its property, the first one winning on a duplicate, read
         * once per version of the file: resolving a key asks for every level, and completion for every key.
         */
        private fun keysOf(file: PsiFile): Map<String, IProperty> =
            CachedValuesManager.getCachedValue(file) {
                val keys = LinkedHashMap<String, IProperty>()
                (file as PropertiesFile).properties.forEach { property ->
                    property.unescapedKey?.let { keys.putIfAbsent(it, property) }
                }
                CachedValueProvider.Result.create(keys, file)
            }
    }
}
