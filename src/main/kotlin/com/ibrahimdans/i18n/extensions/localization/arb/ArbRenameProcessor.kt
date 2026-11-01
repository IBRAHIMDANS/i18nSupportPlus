package com.ibrahimdans.i18n.extensions.localization.arb

import com.intellij.psi.PsiElement
import com.intellij.refactoring.rename.RenamePsiElementProcessor

/**
 * *Rename* (Shift+F6) on a key of a Flutter ARB catalogue renames its `@key` block too:
 * `greeting` → `welcome` moves `@greeting` to `@welcome`, in the same undo. Everything else is the
 * platform's rename of a JSON property.
 */
class ArbRenameProcessor : RenamePsiElementProcessor() {

    override fun canProcessElement(element: PsiElement): Boolean = ArbMetadata.blockOf(element) != null

    override fun prepareRenaming(element: PsiElement, newName: String, allRenames: MutableMap<PsiElement, String>) {
        ArbMetadata.blockOf(element)?.let { allRenames[it] = ArbMetadata.renamedBlock(newName) }
    }
}
