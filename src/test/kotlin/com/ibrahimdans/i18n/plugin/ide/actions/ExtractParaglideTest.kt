package com.ibrahimdans.i18n.plugin.ide.actions

import com.ibrahimdans.i18n.plugin.ide.launchActionAndWait
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.ModuleConfig
import com.intellij.openapi.ui.TestDialogManager
import org.junit.jupiter.api.Test

/**
 * In a Paraglide JS project, *Extract I18n* writes `m.key({ var })` — Paraglide's compiled message
 * function — and imports the message object once, rather than i18next's `t('key')`.
 */
class ExtractParaglideTest : ExtractionTestBase() {

    private val paraglide = ModuleConfig(name = "app", rootDirectory = "src", preset = "paraglide")

    /** Extracts the text at the caret of [source] in [file] as [key]; [prepare] adds files first. */
    private fun extract(source: String, patched: String, key: String = "save_changes", file: String = "save.ts",
                        module: ModuleConfig? = paraglide, prepare: () -> Unit = {}) =
        myFixture.runWithConfig(config("json").copy(modules = listOfNotNull(module))) {
            myFixture.addFileToProject("messages/en.json", "{}")
            prepare()
            myFixture.configureByText(file, source)
            TestDialogManager.setTestInputDialog(predefinedTextInputDialog(key))
            myFixture.launchActionAndWait(myFixture.findSingleIntention(hint))
            myFixture.checkResult(patched)
        }

    @Test
    fun aLiteralBecomesAMessageCallAndTheImportIsAdded() = extract(
        "const a = 'Save <caret>changes';",
        "import { m } from './paraglide/messages.js';\nconst a = m.save_changes();"
    )

    @Test
    fun aTemplateLiteralPassesItsVariable() = extract(
        "import { m } from './paraglide/messages.js';\nexport const greet = (name) => `Hello <caret>\${name}!`;",
        "import { m } from './paraglide/messages.js';\nexport const greet = (name) => m.greeting({ name });",
        key = "greeting"
    )

    /** Already imported, even aliased: the call uses that name and nothing is imported twice. */
    @Test
    fun anExistingImportIsReused() = extract(
        "import { m as msg } from '\$lib/paraglide/messages.js';\nconst a = 'Save <caret>changes';",
        "import { m as msg } from '\$lib/paraglide/messages.js';\nconst a = msg.save_changes();"
    )

    /** A key that is no identifier is written as an index. */
    @Test
    fun aNestedKeyIsWrittenAsAnIndex() = extract(
        "const a = 'Save <caret>changes';",
        "import { m } from './paraglide/messages.js';\nconst a = m[\"form.save\"]();",
        key = "form.save"
    )

    /** The import follows the specifier the project already uses. */
    @Test
    fun theProjectsSpecifierIsReused() = extract(
        "import { x } from './x';\nconst a = 'Save <caret>changes';",
        "import { x } from './x';\nimport { m } from '\$lib/paraglide/messages.js';\nconst a = m.save_changes();",
        prepare = { myFixture.addFileToProject("other.ts", "import { m } from '\$lib/paraglide/messages.js';\nexport const o = m.other();") }
    )

    /** Non-regression: an i18next project keeps `t('key')`. */
    @Test
    fun anI18nextProjectIsUnchanged() = extract(
        "const a = 'Save <caret>changes';",
        "const a = i18n.t('save_changes');",
        module = ModuleConfig(name = "app", rootDirectory = "src", preset = "i18next")
    )
}
