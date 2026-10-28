package com.ibrahimdans.i18n.cli

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class I18nCheckTest {

    private val resources = "src/test/resources"
    private val exampleProject = "../examples/react-multi-namespace"
    private val example = "$exampleProject/public/locales"

    private class Run(val exit: Int, val out: String, val err: String)

    private fun check(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = I18nCheck.run(args.toList(), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
        return Run(exit, out.toString(Charsets.UTF_8).trimEnd(), err.toString(Charsets.UTF_8).trimEnd())
    }

    @Test
    fun `example project - text report of missing and empty keys per namespace`() {
        val run = check("--locales", example)
        assertEquals(
            """
            Reference locale: en (2 locales)

            en: no problem
            fr: 2 missing, 1 empty
              missing  common:actions.eddit
              missing  common:actions.saxve
              empty    navigation:breadrumb.home

            3 problems found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
        assertEquals(I18nCheck.PROBLEMS, run.exit)
    }

    @Test
    fun `example project - JSON report`() {
        val run = check("--locales", example, "--format", "json")
        val expected = """
            {
              "reference": "en",
              "problems": 3,
              "locales": [
                {"locale": "en", "missing": [], "orphans": [], "empty": []},
                {
                  "locale": "fr",
                  "missing": ["common:actions.eddit", "common:actions.saxve"],
                  "orphans": [],
                  "empty": ["navigation:breadrumb.home"]
                }
              ]
            }
        """
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(run.out))
    }

    @Test
    fun `French as the reference - the English-only keys are orphans`() {
        val run = check("--locales", example, "--reference", "fr", "--format", "json")
        val en = JsonParser.parseString(run.out).asJsonObject["locales"].asJsonArray[0].asJsonObject
        assertEquals("[\"common:actions.eddit\",\"common:actions.saxve\"]", en["orphans"].toString())
    }

    @Test
    fun `flat layout - YAML and JSON side by side, other files left alone`() {
        val run = check("--locales", "$resources/flat")
        assertEquals(
            """
            Reference locale: en (2 locales)

            de: 1 missing, 1 orphan, 1 empty
              missing  footer
              orphan   header
              empty    app.subtitle
            en: no problem

            3 problems found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
    }

    @Test
    fun `JSON5 - comments, unquoted keys and single quotes read, the missing key reported`() {
        val run = check("--locales", "$resources/json5")
        assertEquals(
            """
            Reference locale: en (2 locales)

            en: no problem
            fr: 1 missing
              missing  menu.close

            1 problem found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
        assertEquals(I18nCheck.PROBLEMS, run.exit)
    }

    @Test
    fun `TS and JS catalogues - read, the code file beside them left out`() {
        val run = check("--locales", "$resources/ts")
        assertEquals(
            """
            Reference locale: en (2 locales)

            en: no problem
            fr: 1 missing
              missing  menu.close

            1 problem found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
        assertEquals(I18nCheck.PROBLEMS, run.exit)
    }

    @Test
    fun `TS catalogue with a spread - not checked, named with the line, exit 2`() {
        val run = check("--locales", "$resources/ts-partial")
        assertEquals(I18nCheck.CANNOT_CHECK, run.exit)
        assertTrue(run.err.contains("ts-partial/fr.ts: its catalogue is not written as plain literals (spread at line 4)"), run.err)
    }

    @Test
    fun `plural forms - each locale needs the categories of its own language`() {
        val run = check("--locales", "$resources/plurals", "--format", "json")
        val locales = JsonParser.parseString(run.out).asJsonObject["locales"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("en", "ja", "ru"), locales.map { it["locale"].asString })
        assertEquals("[]", locales[1]["missing"].toString())
        assertEquals("[\"item_few\",\"item_many\"]", locales[2]["missing"].toString())
    }

    @Test
    fun `exit code - 0 while the problems stay within the threshold`() {
        assertEquals(I18nCheck.OK, check("--locales", example, "--max-problems", "3").exit)
        assertEquals(I18nCheck.PROBLEMS, check("--locales", example, "--max-problems", "2").exit)
    }

    @Test
    fun `exit code - 0 and a short line when nothing is wrong`() {
        val run = check("--locales", "$resources/clean")
        assertEquals("Reference locale: en (2 locales)\n\nen: no problem\nfr: no problem\n\nNo problem found.", run.out)
        assertEquals(I18nCheck.OK, run.exit)
    }

    @Test
    fun `settings - the module's reference locale and key separator are taken`() {
        val run = check("$resources/settings", "--locales", "$resources/settings/locales", "--format", "json")
        val report = JsonParser.parseString(run.out).asJsonObject
        assertEquals("fr", report["reference"].asString)
        assertEquals("[\"menu/help\"]", report["locales"].asJsonArray[0].asJsonObject["missing"].toString())
    }

    @Test
    fun `settings - the flag overrides the settings' reference locale`() {
        val run = check("$resources/settings", "--locales", "$resources/settings/locales", "--reference", "en", "--format", "json")
        val report = JsonParser.parseString(run.out).asJsonObject
        assertEquals("en", report["reference"].asString)
        assertEquals("[\"menu/help\"]", report["locales"].asJsonArray[1].asJsonObject["orphans"].toString())
    }

    // ── project mode ──────────────────────────────────────────────────────────

    @Test
    fun `project mode - the example's files found without --locales, per locale and namespace`() {
        assertEquals(check("--locales", example).out, check(exampleProject).out)
        val run = check(exampleProject, "--format", "json")
        val fr = JsonParser.parseString(run.out).asJsonObject["locales"].asJsonArray[1].asJsonObject
        assertEquals("[\"common:actions.eddit\",\"common:actions.saxve\"]", fr["missing"].toString())
        assertEquals("[]", fr["orphans"].toString())
        assertEquals("[\"navigation:breadrumb.home\"]", fr["empty"].toString())
        assertEquals(I18nCheck.PROBLEMS, run.exit)
    }

    @Test
    fun `project mode - one section per module, each with its reference locale`() {
        val run = check("$resources/modules")
        assertEquals(
            """
            Module web: reference locale en (2 locales)

            en: no problem
            fr: 1 missing
              missing  cart

            Module admin: reference locale fr (2 locales)

            en: 1 missing, 1 orphan
              missing  roles
              orphan   logs
            fr: no problem

            3 problems found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
        assertEquals(I18nCheck.PROBLEMS, run.exit)
    }

    @Test
    fun `project mode - JSON of several modules`() {
        val run = check("$resources/modules", "--format", "json")
        val expected = """
            {
              "problems": 3,
              "modules": [
                {
                  "module": "web", "reference": "en", "problems": 1,
                  "locales": [
                    {"locale": "en", "missing": [], "orphans": [], "empty": []},
                    {"locale": "fr", "missing": ["cart"], "orphans": [], "empty": []}
                  ]
                },
                {
                  "module": "admin", "reference": "fr", "problems": 2,
                  "locales": [
                    {"locale": "en", "missing": ["roles"], "orphans": ["logs"], "empty": []},
                    {"locale": "fr", "missing": [], "orphans": [], "empty": []}
                  ]
                }
              ]
            }
        """
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(run.out))
    }

    @Test
    fun `project mode - only the translations root is read, the ignored locales are left out`() {
        val run = check("$resources/root")
        assertEquals(
            """
            Reference locale: en (2 locales)

            en: no problem
            fr: 1 missing
              missing  farewell

            1 problem found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
    }

    @Test
    fun `project mode - no translation file, exit 2`() {
        val run = check("$resources/empty")
        assertEquals(I18nCheck.CANNOT_CHECK, run.exit)
        assertTrue(run.err.startsWith("i18n-check: no translation file in $resources/empty"), run.err)
    }

    // ── --code ────────────────────────────────────────────────────────────────

    @Test
    fun `code - the example's unused keys and the call to an unknown one`() {
        val run = check(exampleProject, "--code")
        assertEquals(
            """
            Reference locale: en (2 locales)

            en: no problem
            fr: 2 missing, 1 empty
              missing  common:actions.eddit
              missing  common:actions.saxve
              empty    navigation:breadrumb.home

            Unused keys: 9
              auth:errors.accountLocked
              common:actions.eddit
              common:actions.save
              common:actions.saxve
              common:status.empty
              common:status.error
              common:status.loading
              dashboard:stats.files.label
              navigation:breadcrumb.home

            Unknown keys: 1
              src/components/Dashboard.tsx:28  dashboardd:statss.users.count

            13 problems found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
        assertEquals(I18nCheck.PROBLEMS, run.exit)
    }

    @Test
    fun `code - a key a template literal may build is not unused`() {
        val unused = JsonParser.parseString(check(exampleProject, "--code", "--format", "json").out).asJsonObject["unused"].asJsonArray
        assertTrue(unused.none { it.asString.startsWith("deposit-box:status.") }, unused.toString())
    }

    @Test
    fun `code - JSON adds the unused and unknown keys to the existing fields`() {
        val report = JsonParser.parseString(check(exampleProject, "--code", "--format", "json").out).asJsonObject
        assertEquals(setOf("reference", "problems", "locales", "unused", "unknown"), report.keySet())
        assertEquals(13, report["problems"].asInt)
        assertEquals(9, report["unused"].asJsonArray.size())
        assertEquals(
            JsonParser.parseString("""[{"key": "dashboardd:statss.users.count", "file": "src/components/Dashboard.tsx", "line": 28}]"""),
            report["unknown"],
        )
    }

    @Test
    fun `code - exit code counts the unused and unknown keys`() {
        assertEquals(I18nCheck.OK, check(exampleProject, "--code", "--max-problems", "13").exit)
        assertEquals(I18nCheck.PROBLEMS, check(exampleProject, "--code", "--max-problems", "12").exit)
    }

    /** What the plugin underlines there: each namespace of `useTranslations` prefixes its keys. */
    @Test
    fun `code - next-intl example, the keys read under their namespace`() {
        val run = check("../examples/next-intl", "--code")
        assertTrue(run.out.contains("Unused keys: none"), run.out)
        assertTrue(
            run.out.contains(
                """
                Unknown keys: 3
                  app/[locale]/Cart.tsx:21  discount
                  app/[locale]/PrivacySettings.tsx:20  cookies.expiry
                  app/[locale]/page.tsx:39  doesNotExist
                """.trimIndent(),
            ),
            run.out,
        )
    }

    /** The `.vue` templates and scripts are read, and a component's `<i18n>` block holds keys for its own calls. */
    @Test
    fun `code - vue-i18n example, templates read and component blocks known`() {
        val run = check("../examples/vue-i18n", "--code")
        assertTrue(run.out.contains("Unused keys: none"), run.out)
        assertTrue(
            run.out.contains(
                """
                Unknown keys: 2
                  src/App.vue:21  home.doesNotExist
                  src/components/WelcomeBanner.vue:27  farewell
                """.trimIndent(),
            ),
            run.out,
        )
    }

    /**
     * What the plugin underlines in the Transloco example: the templates' pipes, directives and `read:`
     * blocks are read, and the keys of the `admin` scope file are spelled `admin.…`, as the code writes them.
     */
    @Test
    fun `code - transloco example, templates read and scope keys spelled with their scope`() {
        val run = check("../examples/angular-transloco/transloco", "--code")
        assertTrue(run.out.contains("missing  admin.onlyInEnglish"), run.out)
        assertTrue(run.out.contains("Unused keys: none"), run.out)
        assertTrue(
            run.out.contains(
                """
                Unknown keys: 3
                  src/app/admin/admin-panel.component.html:6  doesNotExist
                  src/app/app.component.html:20  home.doesNotExist
                  src/app/app.component.html:22  title
                """.trimIndent(),
            ),
            run.out,
        )
    }

    @Test
    fun `code - ngx-translate example, pipes, directives and service calls read`() {
        val run = check("../examples/angular-transloco/ngx-translate", "--code")
        assertTrue(run.out.contains("Unused keys: none"), run.out)
        assertTrue(run.out.contains("Unknown keys: 1\n  src/app/app.component.html:18  home.doesNotExist"), run.out)
    }

    @Test
    fun `code - a html file is read as a template in an Angular project only`(@TempDir project: Path) {
        fun write(path: String, text: String) = project.resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }
        write("locales/en.json", """{"a": "A"}""")
        write("src/page.html", "<p>{{ 'a' | translate }}</p>")
        fun unused() = JsonParser.parseString(check(project.toString(), "--code", "--format", "json").out).asJsonObject["unused"].asJsonArray.map { it.asString }

        assertEquals(listOf("a"), unused())
        write("package.json", """{"dependencies": {"@angular/core": "^20.0.0"}}""")
        assertEquals(emptyList<String>(), unused())
    }

    @Test
    fun `code - a module's preset chooses the calls read`(@TempDir project: Path) {
        fun write(path: String, text: String) = project.resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }
        write("app/locales/en.json", """{"a": "A", "b": "B"}""")
        // A `t` taken from no `useI18n()` is not vue-i18n's: under its preset, `a` is unused.
        write("app/src/App.vue", "<script setup>\nimport { t } from './other';\nt('a');\n</script>\n<template>{{ \$t('b') }}</template>")
        write("i18n-check.json", """{"modules": [{"name": "app", "rootDirectory": "app", "preset": "vue-i18n"}]}""")

        val unused = JsonParser.parseString(check(project.toString(), "--code", "--format", "json").out).asJsonObject["unused"].asJsonArray
        assertEquals(listOf("a"), unused.map { it.asString })
    }

    @Test
    fun `code - next-intl's has asks whether a key exists, it is never unknown`(@TempDir project: Path) {
        Files.writeString(project.resolve("en.json"), """{"Nav": {"home": "Home"}}""")
        Files.writeString(project.resolve("Nav.tsx"), "export function Nav() { const t = useTranslations('Nav'); return t.has('beta') ? t('beta') : t('home'); }")

        val unknown = JsonParser.parseString(check(project.toString(), "--code", "--format", "json").out).asJsonObject["unknown"].asJsonArray
        assertEquals(listOf("beta"), unknown.map { it.asJsonObject["key"].asString })
        assertEquals(1, unknown.single().asJsonObject["line"].asInt)
    }

    @Test
    fun `no translation file - a clear message and exit 2`() {
        val run = check("--locales", "$resources/empty")
        assertEquals(I18nCheck.CANNOT_CHECK, run.exit)
        assertTrue(run.err.startsWith("i18n-check: no translation file in $resources/empty"), run.err)
    }

    @Test
    fun `invalid translation file - named, exit 2`() {
        val run = check("--locales", "$resources/invalid")
        assertEquals(I18nCheck.CANNOT_CHECK, run.exit)
        assertTrue(run.err.contains("not a valid JSON translation file"), run.err)
    }

    private fun gbProject(project: Path) {
        fun write(path: String, text: String) = project.resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }
        write("locales/en/common.json", """{"title": "Colour", "item_one": "{{count}} item", "item_other": "{{count}} items"}""")
        write("locales/fr/common.json", """{"title": "Couleur", "item_one": "{{count}} élément", "item_other": "{{count}} éléments"}""")
        write("locales/gb/common.json", """{"item_other": "{{count}} items"}""")
    }

    @Test
    fun `country code folder - not checked without an alias, and said so`(@TempDir project: Path) {
        gbProject(project)

        val run = check(project.toString())

        assertEquals(I18nCheck.OK, run.exit, run.out)
        assertTrue(run.out.contains("(2 locales)"), run.out)
        assertTrue(run.err.contains("locales/gb not checked, 'gb' is a country code, not a language code"), run.err)
    }

    @Test
    fun `country code folder - checked under its alias, with the plural forms of its locale`(@TempDir project: Path) {
        gbProject(project)
        Files.writeString(project.resolve(SettingsFile.JSON_PATH), """{"localeAliases": "gb=en-GB"}""")

        val run = check(project.toString())

        assertEquals(I18nCheck.PROBLEMS, run.exit, run.out)
        assertTrue(run.out.contains("(3 locales)"), run.out)
        assertTrue(run.out.contains("gb: 2 missing"), run.out)
        assertTrue(run.out.contains("missing  common:item_one"), run.out)
        assertTrue(run.out.contains("missing  common:title"), run.out)
        assertTrue(run.err.isEmpty(), run.err)
    }

    @Test
    fun `project mode - a module put aside has no section and its files are not read`(@TempDir project: Path) {
        fun write(path: String, text: String) = project.resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }
        write("apps/web/locales/en.json", """{"a": "A", "b": "B"}""")
        write("apps/web/locales/fr.json", """{"a": "A"}""")
        write("apps/admin/locales/en.json", """{"x": "X"}""")
        write("apps/admin/locales/fr.json", "not json")
        write(
            SettingsFile.JSON_PATH,
            """{"modules": [
                {"name": "web", "rootDirectory": "apps/web", "pathTemplate": "locales/{lang}.json"},
                {"name": "admin", "rootDirectory": "apps/admin", "pathTemplate": "locales/{lang}.json", "enabled": false}
            ]}""",
        )

        val run = check(project.toString())

        assertEquals(I18nCheck.PROBLEMS, run.exit, run.err)
        assertTrue(run.out.contains("missing  b"), run.out)
        assertTrue("admin" !in run.out, run.out)
    }

    @Test
    fun `invalid i18n-check json - named, exit 2`(@TempDir project: Path) {
        Files.writeString(project.resolve(SettingsFile.JSON_PATH), """{ "keySeperator": "/" }""")
        val run = check(project.toString())
        assertEquals(I18nCheck.CANNOT_CHECK, run.exit)
        assertTrue(run.err.startsWith("i18n-check: cannot read i18n-check.json: unknown setting 'keySeperator'"), run.err)
    }

    @Test
    fun `unknown reference locale - exit 2`() {
        val run = check("--locales", example, "--reference", "de")
        assertEquals(I18nCheck.CANNOT_CHECK, run.exit)
        assertEquals("i18n-check: reference locale de not found among en, fr", run.err)
    }

    @Test
    fun `usage errors - exit 2 with the usage`() {
        listOf(
            arrayOf("--locales"),
            arrayOf("--locales", example, "--code"),
            arrayOf("--locales", example, "--format", "xml"),
            arrayOf("--locales", example, "--max-problems", "-1"),
            arrayOf("--locales", example, "--verbose"),
            arrayOf("a", "b", "--locales", example),
        ).forEach { args ->
            val run = check(*args)
            assertEquals(I18nCheck.CANNOT_CHECK, run.exit, args.joinToString(" "))
            assertTrue(run.err.contains("Usage: i18n-check"), run.err)
        }
    }

    @Test
    fun `help - the usage on the standard output, exit 0`() {
        val run = check("--help")
        assertEquals(I18nCheck.OK, run.exit)
        assertTrue(run.out.startsWith("Usage: i18n-check"))
    }
}
