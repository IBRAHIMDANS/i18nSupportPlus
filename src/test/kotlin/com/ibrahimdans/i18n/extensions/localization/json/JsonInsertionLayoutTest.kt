package com.ibrahimdans.i18n.extensions.localization.json

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import com.ibrahimdans.i18n.plugin.key.FullKey
import com.ibrahimdans.i18n.plugin.key.lexer.Literal
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.command.WriteCommandAction
import org.junit.jupiter.api.Test

/**
 * A key added to a JSON translation file takes the layout of the file around it — its indent
 * (spaces or tabs, whatever their number), one line or several — and nothing else in the file
 * moves. The test platform runs with the IDE's default JSON code style (2 spaces), the style the
 * insertion used to follow whatever the file: the 4-space and tab cases prove it no longer does.
 */
class JsonInsertionLayoutTest : PlatformBaseTest() {

    private val generator = JsonLocalization().contentGenerator()

    private fun root(): JsonObject = (myFixture.file as JsonFile).topLevelValue as JsonObject

    private fun add(key: String, value: String, target: () -> JsonObject = ::root) {
        WriteCommandAction.runWriteCommandAction(project) {
            generator.generateTranslationEntry(target(), key, "\"$value\"")
        }
    }

    /** Creates the chain [path] under [target], the way the dialog and *Fill Missing* do. */
    private fun create(path: List<String>, value: String, target: () -> JsonObject = ::root) {
        WriteCommandAction.runWriteCommandAction(project) {
            val literals = path.map { Literal(it) }
            generator.generate(target(), FullKey(path.joinToString("."), null, literals), literals, value)
        }
    }

    private fun nested(name: String): JsonObject = root().findProperty(name)!!.value as JsonObject

    @Test
    fun twoSpaceFileKeepsTwoSpaces() {
        myFixture.configureByText("en.json", "{\n  \"a\": \"A\"\n}")
        add("b", "B")
        myFixture.checkResult("{\n  \"a\": \"A\",\n  \"b\": \"B\"\n}")
    }

    @Test
    fun fourSpaceFileKeepsFourSpaces() {
        myFixture.configureByText("en.json", "{\n    \"a\": \"A\"\n}")
        add("b", "B")
        myFixture.checkResult("{\n    \"a\": \"A\",\n    \"b\": \"B\"\n}")
    }

    @Test
    fun tabIndentedFileKeepsTabs() {
        myFixture.configureByText("en.json", "{\n\t\"a\": \"A\",\n\t\"n\": {\n\t\t\"x\": \"X\"\n\t}\n}")
        add("y", "Y") { nested("n") }
        add("b", "B")
        myFixture.checkResult("{\n\t\"a\": \"A\",\n\t\"n\": {\n\t\t\"x\": \"X\",\n\t\t\"y\": \"Y\"\n\t},\n\t\"b\": \"B\"\n}")
    }

    @Test
    fun oneLineObjectStaysOnOneLine() {
        myFixture.configureByText("en.json", "{ \"a\": \"A\" }")
        add("b", "B")
        myFixture.checkResult("{ \"a\": \"A\", \"b\": \"B\" }")
    }

    @Test
    fun oneLineNestedObjectInAFourSpaceFileStaysOnOneLine() {
        myFixture.configureByText("en.json", "{\n    \"title\":   \"Titel\",\n    \"nested\": { \"a\": \"A-de\" }\n}")
        add("b", "B-de") { nested("nested") }
        add("cancel", "Abbrechen")
        myFixture.checkResult(
            "{\n    \"title\":   \"Titel\",\n    \"nested\": { \"a\": \"A-de\", \"b\": \"B-de\" },\n    \"cancel\": \"Abbrechen\"\n}"
        )
    }

    @Test
    fun nestedKeyCreatedWithTheFileIndent() {
        myFixture.configureByText("en.json", "{\n    \"title\":   \"Titel\"\n}")
        create(listOf("save", "label", "short"), "Save")
        myFixture.checkResult(
            "{\n    \"title\":   \"Titel\",\n    \"save\": {\n        \"label\": {\n            \"short\": \"Save\"\n        }\n    }\n}"
        )
    }

    @Test
    fun keyAddedToANestedObjectTakesItsIndent() {
        myFixture.configureByText("en.json", "{\n    \"nested\": {\n        \"a\": \"A\"\n    }\n}")
        add("b", "B") { nested("nested") }
        myFixture.checkResult("{\n    \"nested\": {\n        \"a\": \"A\",\n        \"b\": \"B\"\n    }\n}")
    }

    @Test
    fun sortedInsertionInFrontKeepsTheFileIndent() = myFixture.runWithConfig(Config(extractSorted = true)) {
        myFixture.configureByText("en.json", "{\n    \"b\": \"B\",\n    \"c\": \"C\"\n}")
        add("a", "A")
        myFixture.checkResult("{\n    \"a\": \"A\",\n    \"b\": \"B\",\n    \"c\": \"C\"\n}")
    }

    @Test
    fun sortedInsertionInTheMiddleKeepsTheFileIndent() = myFixture.runWithConfig(Config(extractSorted = true)) {
        myFixture.configureByText("en.json", "{\n    \"a\": \"A\",\n    \"c\": \"C\"\n}")
        add("b", "B")
        myFixture.checkResult("{\n    \"a\": \"A\",\n    \"b\": \"B\",\n    \"c\": \"C\"\n}")
    }

    @Test
    fun emptyObjectInAFourSpaceFileTakesTheFileUnit() {
        myFixture.configureByText("en.json", "{\n    \"a\": \"A\",\n    \"n\": {}\n}")
        add("x", "X") { nested("n") }
        myFixture.checkResult("{\n    \"a\": \"A\",\n    \"n\": {\n        \"x\": \"X\"\n    }\n}")
    }

    @Test
    fun emptyFileGetsTheDefaultIndent() {
        myFixture.configureByText("en.json", "{}")
        add("a", "A")
        myFixture.checkResult("{\n  \"a\": \"A\"\n}")
    }

    /** Odd spacing elsewhere in the file — aligned values, a blank line — is left as it is. */
    @Test
    fun existingContentIsUntouchedOutsideTheAddition() {
        val before = "{\n    \"title\":   \"Titel\",\n\n    \"quoted\" : \"Zitat\",\n    \"n\": {\"x\":\"X\"}\n}"
        myFixture.configureByText("en.json", before)
        add("z", "Z")
        myFixture.checkResult("{\n    \"title\":   \"Titel\",\n\n    \"quoted\" : \"Zitat\",\n    \"n\": {\"x\":\"X\"},\n    \"z\": \"Z\"\n}")
    }

    /** With *Sort keys alphabetically*, the addition moves into place and nothing else is reformatted. */
    @Test
    fun insertionWithSortKeepsTheFileIndent() = myFixture.runWithConfig(Config(sortKeysAlphabetically = true)) {
        myFixture.configureByText("en.json", "{\n    \"a\":   \"A\",\n    \"c\": \"C\"\n}")
        add("b", "B")
        myFixture.checkResult("{\n    \"a\":   \"A\",\n    \"b\": \"B\",\n    \"c\": \"C\"\n}")
    }
}
