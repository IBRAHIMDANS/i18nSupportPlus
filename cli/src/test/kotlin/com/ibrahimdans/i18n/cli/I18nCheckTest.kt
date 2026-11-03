package com.ibrahimdans.i18n.cli

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    /**
     * The 1-based line where [fragment] is written, once, in [path] under [project]. The examples are
     * documentation and get edited (#698 added a comment line): reading the line off the file keeps
     * these tests about the line the report gives, not about the example's layout.
     */
    private fun lineOf(project: String, path: String, fragment: String): Int {
        val text = java.io.File(project, path).readText()
        val offset = text.indexOf(fragment)
        check(offset >= 0 && text.indexOf(fragment, offset + 1) < 0) { "'$fragment' is not written once in $path" }
        return text.substring(0, offset).count { it == '\n' } + 1
    }

    private val unknownKeyLine get() = lineOf(exampleProject, "src/components/Dashboard.tsx", "'dashboardd:statss.users.count'")
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
                {"locale": "en", "missing": [], "orphans": [], "empty": [], "values": []},
                {
                  "locale": "fr",
                  "missing": ["common:actions.eddit", "common:actions.saxve"],
                  "orphans": [],
                  "empty": ["navigation:breadrumb.home"],
                  "values": []
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
    fun `PO catalogues - msgctxt keys apart, a plural form left blank is empty, header and obsolete entries skipped`() {
        val run = check("--locales", "$resources/po")
        assertEquals(
            """
            Reference locale: en (2 locales)

            en: no problem
            fr: 1 missing, 1 orphan, 2 empty
              missing  Open
              orphan   Legacy
              empty    Open (msgctxt: menu)
              empty    {count} item

            4 problems found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
        assertEquals(I18nCheck.PROBLEMS, run.exit)
    }

    @Test
    fun `Laravel PHP arrays - nested arrays are nested keys, the file names the namespace`() {
        val run = check("--locales", "$resources/php")
        assertEquals(
            """
            Reference locale: en (2 locales)

            en: no problem
            fr: 1 missing, 1 orphan, 1 empty
              missing  messages:auth.throttle
              orphan   messages:legacy
              empty    messages:welcome

            3 problems found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
        assertEquals(I18nCheck.PROBLEMS, run.exit)
    }

    /** The override's `sent` is empty in `fr`, the application's is not: two keys, never mixed. */
    @Test
    fun `Laravel package override - read under the package's namespace, apart from the application's file`() {
        val run = check("$resources/php-vendor")
        assertEquals(
            """
            Reference locale: en (2 locales)

            en: no problem
            fr: 1 empty
              empty    courier::messages:sent

            1 problem found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
    }

    @Test
    fun `Laravel PHP array built by code - not checked, named with the line, exit 2`() {
        val run = check("--locales", "$resources/php-partial")
        assertEquals(I18nCheck.CANNOT_CHECK, run.exit)
        assertTrue(run.err.contains("php-partial/en/messages.php: its catalogue is not written as plain literals (concatenation at line 5)"), run.err)
    }

    @Test
    fun `ARB catalogues - locale from @@locale or the name, metadata no key`() {
        val run = check("--locales", "$resources/arb")
        assertEquals(
            """
            Reference locale: en (3 locales)

            de: no problem
            en: no problem
            fr: 1 missing, 1 orphan, 1 empty
              missing  title
              orphan   old
              empty    farewell

            3 problems found (allowed: 0).
            """.trimIndent(),
            run.out,
        )
        assertEquals(I18nCheck.PROBLEMS, run.exit)
    }

    @Test
    fun `project mode - Flutter catalogues found under lib, the build copies left out`(@TempDir project: Path) {
        fun write(path: String, text: String) = project.resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }
        write("lib/l10n/app_en.arb", """{"@@locale": "en", "title": "App", "@title": {}, "hello": "Hello"}""")
        write("lib/l10n/app_fr.arb", """{"title": "Appli"}""")
        // Read, this copy would stop the check: it is not JSON.
        write(".dart_tool/flutter_gen/gen_l10n/app_en.arb", "{")

        val run = check(project.toString(), "--format", "json")

        assertEquals(I18nCheck.PROBLEMS, run.exit, run.err)
        val locales = JsonParser.parseString(run.out).asJsonObject["locales"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("en", "fr"), locales.map { it["locale"].asString })
        assertEquals("[\"hello\"]", locales[1]["missing"].toString())
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
    fun `plural forms - the bare key stands in for the forms a group lacks, as i18next falls back on it`(@TempDir dir: Path) {
        // Crowdin's export: `ago` beside `ago_other`, never `ago_one`.
        Files.writeString(dir.resolve("en.json"), """{"ago": "{{count}} minute ago", "ago_other": "{{count}} minutes ago"}""")
        Files.writeString(dir.resolve("ru.json"), """{"ago": "{{count}} минуту назад", "ago_other": "{{count}} минут назад"}""")
        Files.writeString(dir.resolve("fr.json"), """{"ago_other": "il y a {{count}} minutes"}""")

        val run = check("--locales", dir.toString(), "--format", "json")

        val missing = JsonParser.parseString(run.out).asJsonObject["locales"].asJsonArray
            .associate { it.asJsonObject["locale"].asString to it.asJsonObject["missing"].toString() }
        assertEquals("[]", missing["en"], "the bare key covers `one`")
        assertEquals("[]", missing["ru"], "and `few` and `many`")
        assertEquals("[\"ago\",\"ago_one\"]", missing["fr"], "without the bare key, `one` is missing")
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
                    {"locale": "en", "missing": [], "orphans": [], "empty": [], "values": []},
                    {"locale": "fr", "missing": ["cart"], "orphans": [], "empty": [], "values": []}
                  ]
                },
                {
                  "module": "admin", "reference": "fr", "problems": 2,
                  "locales": [
                    {"locale": "en", "missing": ["roles"], "orphans": ["logs"], "empty": [], "values": []},
                    {"locale": "fr", "missing": [], "orphans": [], "empty": [], "values": []}
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
              src/components/Dashboard.tsx:$unknownKeyLine  dashboardd:statss.users.count

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
            JsonParser.parseString("""[{"key": "dashboardd:statss.users.count", "file": "src/components/Dashboard.tsx", "line": $unknownKeyLine}]"""),
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
        val project = "../examples/next-intl"
        val run = check(project, "--code")
        assertTrue(run.out.contains("Unused keys: none"), run.out)
        assertTrue(
            run.out.contains(
                """
                Unknown keys: 3
                  app/[locale]/Cart.tsx:${lineOf(project, "app/[locale]/Cart.tsx", "'discount'")}  discount
                  app/[locale]/PrivacySettings.tsx:${lineOf(project, "app/[locale]/PrivacySettings.tsx", "'cookies.expiry'")}  cookies.expiry
                  app/[locale]/page.tsx:${lineOf(project, "app/[locale]/page.tsx", "'doesNotExist'")}  doesNotExist
                """.trimIndent(),
            ),
            run.out,
        )
    }

    /** The `.vue` templates and scripts are read, and a component's `<i18n>` block holds keys for its own calls. */
    @Test
    fun `code - vue-i18n example, templates read and component blocks known`() {
        val project = "../examples/vue-i18n"
        val run = check(project, "--code")
        assertTrue(run.out.contains("Unused keys: none"), run.out)
        assertTrue(
            run.out.contains(
                """
                Unknown keys: 2
                  src/App.vue:${lineOf(project, "src/App.vue", "'home.doesNotExist'")}  home.doesNotExist
                  src/components/WelcomeBanner.vue:${lineOf(project, "src/components/WelcomeBanner.vue", "'farewell'")}  farewell
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

    /** What the plugin underlines in the react-intl example: descriptor ids, inline and catalogued, and `<FormattedMessage id>`; `defaultMessage` is no key. */
    @Test
    fun `code - react-intl example, descriptors and FormattedMessage read from the preset`() {
        val project = "../examples/react-intl"
        val run = check(project, "--code")
        assertTrue(run.out.contains("missing  home.only_in_english"), run.out)
        assertTrue(run.out.contains("Unused keys: none"), run.out)
        assertTrue(
            run.out.contains(
                """
                Unknown keys: 3
                  src/App.tsx:${lineOf(project, "src/App.tsx", "\"home.does_not_exist\"")}  home.does_not_exist
                  src/Cart.tsx:${lineOf(project, "src/Cart.tsx", "\"cart.does_not_exist\"")}  cart.does_not_exist
                  src/Orders.tsx:${lineOf(project, "src/Orders.tsx", "\"orders.status.does_not_exist\"")}  orders.status.does_not_exist
                """.trimIndent(),
            ),
            run.out,
        )
    }

    /** What the plugin underlines in the Lingui example: `i18n._`, `msg` once Lingui is imported, and the source-text `<Trans>` under the msgid the macro builds. */
    @Test
    fun `code - lingui example, PO catalogues against msg, i18n _ and source-text Trans`() {
        val project = "../examples/lingui"
        val run = check(project, "--code")
        assertTrue(run.out.contains("missing  messages:about.beta"), run.out)
        assertTrue(run.out.contains("Unused keys: none"), run.out)
        assertTrue(
            run.out.contains("Unknown keys: 1\n  src/App.tsx:${lineOf(project, "src/App.tsx", "\"checkout.coupon\"")}  checkout.coupon"),
            run.out,
        )
    }

    @Test
    fun `code - ngx-translate example, pipes, directives and service calls read`() {
        val run = check("../examples/angular-transloco/ngx-translate", "--code")
        assertTrue(run.out.contains("Unused keys: none"), run.out)
        assertTrue(run.out.contains("Unknown keys: 1\n  src/app/app.component.html:18  home.doesNotExist"), run.out)
    }

    /** What the plugin underlines in the svelte-i18n example: `$_`, `$t`, `$format` and `$json` in scripts and markup, `$_` unwrapped in a `.ts`. */
    @Test
    fun `code - svelte-i18n example, components' scripts and markup read`() {
        val project = "../examples/svelte-i18n"
        val run = check(project, "--code")
        assertTrue(run.out.contains("missing  only_in_english"), run.out)
        assertTrue(run.out.contains("Unused keys: none"), run.out)
        assertTrue(
            run.out.contains("Unknown keys: 1\n  src/App.svelte:${lineOf(project, "src/App.svelte", "\"does.not.exist\"")}  does.not.exist"),
            run.out,
        )
    }

    /**
     * What the plugin underlines in the Laravel example: helpers, the translator and Blade's directives,
     * a file's key under its file's namespace, a JSON phrase whole. The package override, `courier::messages:sent`,
     * is used by `__('courier::messages.sent')` and, overridden in `en` only, is not missing in `fr`; the key of a
     * package without an override, `courier::tracking.link`, is neither read nor reported.
     */
    @Test
    fun `code - laravel example, PHP calls and Blade views read`() {
        val project = "../examples/laravel"
        val run = check(project, "--code")
        assertTrue(run.out.contains("Unused keys: none"), run.out)
        assertFalse(run.out.contains("courier::"), run.out)
        assertTrue(
            run.out.contains(
                """
                Unknown keys: 3
                  app/Http/Controllers/HomeController.php:${lineOf(project, "app/Http/Controllers/HomeController.php", "'messages.does_not_exist'")}  messages.does_not_exist
                  resources/views/cart.blade.php:${lineOf(project, "resources/views/cart.blade.php", "'messages.cart.lines'")}  messages.cart.lines
                  resources/views/welcome.blade.php:${lineOf(project, "resources/views/welcome.blade.php", "'messages.not_in_any_file'")}  messages.not_in_any_file
                """.trimIndent(),
            ),
            run.out,
        )
    }

    /**
     * A package's key is read under the namespace of its override, an unknown one reported as written; its keys are
     * missing only in a locale overriding the package file without them, not in one overriding nothing of the package.
     */
    @Test
    fun `code - Laravel package override keys read, missing only where the package file is overridden`(@TempDir project: Path) {
        fun write(path: String, text: String) = project.resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }
        write("composer.json", """{"require": {"laravel/framework": "^13.0"}}""")
        write("lang/en/messages.php", "<?php\nreturn ['sent' => 'Sent'];\n")
        write("lang/fr/messages.php", "<?php\nreturn ['sent' => 'Envoyé'];\n")
        write("lang/de/messages.php", "<?php\nreturn ['sent' => 'Gesendet'];\n")
        write("lang/vendor/courier/en/messages.php", "<?php\nreturn ['sent' => 'On its way', 'late' => 'Late'];\n")
        write("lang/vendor/courier/fr/messages.php", "<?php\nreturn ['sent' => 'En route'];\n")
        write("app/page.php", "<?php\necho __('messages.sent') . __('courier::messages.sent') . __('courier::messages.late');\necho __('courier::messages.unknown') . __('mailer::messages.sent');\n")

        val report = JsonParser.parseString(check(project.toString(), "--code", "--format", "json").out).asJsonObject
        assertEquals(emptyList<String>(), report["unused"].asJsonArray.map { it.asString })
        assertEquals(listOf("courier::messages.unknown"), report["unknown"].asJsonArray.map { it.asJsonObject["key"].asString })
        val missing = report["locales"].asJsonArray.associate { locale ->
            locale.asJsonObject["locale"].asString to locale.asJsonObject["missing"].asJsonArray.map { it.asString }
        }
        // `fr` overrides the file without `late`; `de` overrides nothing of `courier` and keeps the package's own.
        assertEquals(mapOf("de" to emptyList(), "en" to emptyList(), "fr" to listOf("courier::messages:late")), missing)
    }

    @Test
    fun `code - php files are read in a Laravel project only`(@TempDir project: Path) {
        fun write(path: String, text: String) = project.resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }
        write("lang/en.json", """{"Hello": "Hello"}""")
        write("app/page.php", "<?php echo __('Hello');")
        fun unused() = JsonParser.parseString(check(project.toString(), "--code", "--format", "json").out).asJsonObject["unused"].asJsonArray.map { it.asString }

        assertEquals(listOf("Hello"), unused())
        write("composer.json", """{"require": {"laravel/framework": "^13.0"}}""")
        assertEquals(emptyList<String>(), unused())
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

    /** The `--code` JSON report of a monorepo with `apps/web` and `apps/admin`, and the [extra] modules, written by [files]. */
    private fun monorepo(project: Path, extra: String = "", files: (write: (String, String) -> Unit) -> Unit): com.google.gson.JsonObject {
        fun write(path: String, text: String) = project.resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }
        write(
            "i18n-check.json",
            """{"modules": [{"name": "web", "rootDirectory": "apps/web"}, {"name": "admin", "rootDirectory": "apps/admin"}$extra]}""",
        )
        files(::write)
        return JsonParser.parseString(check(project.toString(), "--code", "--format", "json").out).asJsonObject
    }

    private fun com.google.gson.JsonObject.unusedOf(module: String): List<String> =
        this["modules"].asJsonArray.map { it.asJsonObject }.single { it["module"].asString == module }["unused"].asJsonArray.map { it.asString }

    @Test
    fun `code - in a monorepo, a module's key used by another module's code only is unused`(@TempDir project: Path) {
        val report = monorepo(project) { write ->
            write("apps/web/locales/en.json", """{"title": "Web", "home": "Home"}""")
            write("apps/web/src/App.tsx", "t('home');\n")
            write("apps/admin/locales/en.json", """{"title": "Admin"}""")
            write("apps/admin/src/App.tsx", "t('title');\n")
        }
        assertEquals(listOf("title"), report.unusedOf("web"))
        assertEquals(emptyList<String>(), report.unusedOf("admin"))
        assertEquals(0, report["unknown"].asJsonArray.size())
    }

    @Test
    fun `code - in a monorepo, a call to a key of another module only is unknown, with its file and line`(@TempDir project: Path) {
        val report = monorepo(project) { write ->
            write("apps/web/locales/en.json", """{"billing": {"total": "Total"}}""")
            write("apps/web/src/App.tsx", "t('billing.total');\n")
            write("apps/admin/locales/en.json", """{"title": "Admin"}""")
            write("apps/admin/src/App.tsx", "t('title');\nt('billing.total');\n")
        }
        val unknown = report["unknown"].asJsonArray.single().asJsonObject
        assertEquals("billing.total", unknown["key"].asString)
        assertEquals("apps/admin/src/App.tsx", unknown["file"].asString)
        assertEquals(2, unknown["line"].asInt)
    }

    @Test
    fun `code - in a monorepo, a key built at runtime only reaches the keys of its own module`(@TempDir project: Path) {
        val report = monorepo(project) { write ->
            write("apps/web/locales/en.json", """{"status": {"open": "Open"}}""")
            write("apps/admin/locales/en.json", """{"status": {"open": "Open"}}""")
            write("apps/admin/src/App.tsx", "t(`status.\${state}`);\n")
        }
        assertEquals(listOf("status.open"), report.unusedOf("web"))
        assertEquals(emptyList<String>(), report.unusedOf("admin"))
    }

    @Test
    fun `code - a shared package's keys are used by the code of its codeRoots`(@TempDir project: Path) {
        val shared = """, {"name": "i18n", "rootDirectory": "packages/i18n", "codeRoots": "apps/web"}"""
        val report = monorepo(project, shared) { write ->
            write("apps/web/locales/en.json", """{"home": "Home"}""")
            write("apps/web/src/App.tsx", "t('home');\nt('save');\n")
            write("apps/admin/locales/en.json", """{"title": "Admin"}""")
            write("apps/admin/src/App.tsx", "t('title');\nt('cancel');\n")
            write("packages/i18n/locales/en.json", """{"save": "Save", "cancel": "Cancel"}""")
        }
        assertEquals(listOf("cancel"), report.unusedOf("i18n"))
        // `cancel` is no key of admin's: its code is not the shared package's.
        assertEquals(listOf("cancel"), report["unknown"].asJsonArray.map { it.asJsonObject["key"].asString })
    }

    @Test
    fun `code - a file outside every module is checked against every key`(@TempDir project: Path) {
        val report = monorepo(project) { write ->
            write("apps/web/locales/en.json", """{"home": "Home"}""")
            write("apps/admin/locales/en.json", """{"title": "Admin"}""")
            write("scripts/seed.ts", "t('home');\nt('title');\nt('gone');\n")
        }
        assertEquals(listOf("home"), report.unusedOf("web"))
        assertEquals(listOf("gone"), report["unknown"].asJsonArray.map { it.asJsonObject["key"].asString })
    }

    @Test
    fun `code - a key kept in a constant counts, imported or not, and an unknown one is reported at the call`(@TempDir project: Path) {
        fun write(path: String, text: String) = project.resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }
        write("locales/en/common.json", """{"actions": {"save": "Save"}, "page": {"home": "Home"}, "unused": "Unused"}""")
        write("src/keys.ts", "export const KEYS = { save: 'actions.save', gone: 'actions.gone' } as const;\n")
        write("src/App.tsx", "import { KEYS } from './keys';\nenum Titles { Home = 'page.home' }\nconst { t } = useTranslation('common');\nt(KEYS.save);\nt(Titles.Home);\nt(KEYS.gone);\n")

        val report = JsonParser.parseString(check(project.toString(), "--code", "--format", "json").out).asJsonObject
        assertEquals(listOf("common:unused"), report["unused"].asJsonArray.map { it.asString })
        val unknown = report["unknown"].asJsonArray.single().asJsonObject
        assertEquals("actions.gone", unknown["key"].asString)
        assertEquals(6, unknown["line"].asInt)
    }

    @Test
    fun `code - a constant imported by a tsconfig path alias counts, comments and trailing commas allowed`(@TempDir project: Path) {
        fun write(path: String, text: String) = project.resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }
        write("locales/en/common.json", """{"actions": {"save": "Save"}, "page": {"home": "Home"}}""")
        write("src/i18n/keys.ts", "export const KEYS = { save: 'actions.save' } as const;\n")
        write("src/i18n/titles.ts", "export enum Titles { Home = 'page.home' }\n")
        write("src/app/page.tsx", "import { KEYS } from '@/i18n/keys';\nimport { Titles } from '~titles';\nconst { t } = useTranslation('common');\nt(KEYS.save);\nt(Titles.Home);\n")
        write(
            "tsconfig.json",
            """
            {
              // create-next-app's alias
              "compilerOptions": {
                "baseUrl": ".", /* the project's root */
                "paths": { "@/*": ["./src/*"], "~titles": ["src/i18n/titles.ts"], },
              },
            }
            """.trimIndent(),
        )

        val report = JsonParser.parseString(check(project.toString(), "--code", "--format", "json").out).asJsonObject
        assertEquals(emptyList<String>(), report["unused"].asJsonArray.map { it.asString })
        assertEquals(0, report["unknown"].asJsonArray.size())
    }

    @Test
    fun `code - a jsconfig alias is read, and without paths or with another alias the constant stays unread`(@TempDir project: Path) {
        fun write(path: String, text: String) = project.resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }
        write("locales/en/common.json", """{"actions": {"save": "Save"}}""")
        write("src/keys.js", "export const KEYS = { save: 'actions.save' };\n")
        write("src/App.jsx", "import { KEYS } from '@/keys';\nconst { t } = useTranslation('common');\nt(KEYS.save);\n")
        fun unused() = JsonParser.parseString(check(project.toString(), "--code", "--format", "json").out).asJsonObject["unused"].asJsonArray.map { it.asString }

        write("jsconfig.json", """{"compilerOptions": {"paths": {"@/*": ["src/*"]}}}""")
        assertEquals(emptyList<String>(), unused())
        write("jsconfig.json", """{"compilerOptions": {"baseUrl": "."}}""")
        assertEquals(listOf("common:actions.save"), unused())
        write("jsconfig.json", """{"compilerOptions": {"paths": {"#/*": ["src/*"]}}}""")
        assertEquals(listOf("common:actions.save"), unused())
    }

    @Test
    fun `code - a path alias inherited through extends counts, read from the folder of the file declaring it`(@TempDir project: Path) {
        fun write(path: String, text: String) = project.resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }
        write("locales/en/common.json", """{"actions": {"save": "Save"}}""")
        write("src/i18n/keys.ts", "export const KEYS = { save: 'actions.save' } as const;\n")
        write("src/App.tsx", "import { KEYS } from '@/i18n/keys';\nconst { t } = useTranslation('common');\nt(KEYS.save);\n")
        fun unused() = JsonParser.parseString(check(project.toString(), "--code", "--format", "json").out).asJsonObject["unused"].asJsonArray.map { it.asString }

        // Simple: the root tsconfig.json only extends the base, which holds the paths (`.json` left out of `extends`).
        write("config/tsconfig.base.json", """{"compilerOptions": {"baseUrl": "..", "paths": {"@/*": ["src/*"]}}}""")
        write("tsconfig.json", """{"extends": "./config/tsconfig.base", "compilerOptions": {"strict": true}}""")
        assertEquals(emptyList<String>(), unused())

        // Chained, and `paths` without `baseUrl` resolve from the folder of the file declaring them.
        write("config/tsconfig.paths.json", """{"compilerOptions": {"paths": {"@/*": ["../src/*"]}}}""")
        write("config/tsconfig.base.json", """{"extends": ["@tsconfig/node20/tsconfig.json", "./tsconfig.paths.json"]}""")
        assertEquals(emptyList<String>(), unused())

        // The nearest file wins: an override of `paths` that names another folder leaves the constant unread.
        write("tsconfig.json", """{"extends": "./config/tsconfig.base.json", "compilerOptions": {"paths": {"@/*": ["lib/*"]}}}""")
        assertEquals(listOf("common:actions.save"), unused())
    }

    @Test
    fun `code - a cycle of extends is read once, and an extends naming a package is left out`(@TempDir project: Path) {
        fun write(path: String, text: String) = project.resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }
        write("locales/en/common.json", """{"actions": {"save": "Save"}}""")
        write("src/keys.ts", "export const KEYS = { save: 'actions.save' } as const;\n")
        write("src/App.tsx", "import { KEYS } from '@/keys';\nconst { t } = useTranslation('common');\nt(KEYS.save);\n")
        write("tsconfig.json", """{"extends": "./tsconfig.base.json"}""")
        write("tsconfig.base.json", """{"extends": ["@tsconfig/strictest", "./tsconfig.json"], "compilerOptions": {"paths": {"@/*": ["./src/*"]}}}""")

        val report = JsonParser.parseString(check(project.toString(), "--code", "--format", "json").out).asJsonObject
        assertEquals(emptyList<String>(), report["unused"].asJsonArray.map { it.asString })
    }

    @Test
    fun `code - each package of a monorepo reads the path aliases of its own tsconfig`(@TempDir project: Path) {
        fun write(path: String, text: String) = project.resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) }
        write("locales/en/common.json", """{"actions": {"save": "Save", "open": "Open"}}""")
        write("tsconfig.base.json", """{"compilerOptions": {"paths": {"#shared/*": ["./packages/shared/*"]}}}""")
        write("tsconfig.json", """{"extends": "./tsconfig.base.json"}""")
        write("packages/shared/keys.ts", "export const KEYS = { save: 'actions.save' } as const;\n")
        write("packages/web/tsconfig.json", """{"extends": "../../tsconfig.base.json", "compilerOptions": {"baseUrl": ".", "paths": {"~/*": ["src/*"]}}}""")
        write("packages/web/src/keys.ts", "export const OPEN = { open: 'actions.open' } as const;\n")
        write(
            "packages/web/src/App.tsx",
            "import { OPEN } from '~/keys';\nconst { t } = useTranslation('common');\nt(OPEN.open);\n",
        )
        write("apps/admin/Admin.tsx", "import { KEYS } from '#shared/keys';\nconst { t } = useTranslation('common');\nt(KEYS.save);\n")

        // `~/*` comes from the package's own file, `#shared/*` from the root's, read by `apps/admin` which has none.
        val report = JsonParser.parseString(check(project.toString(), "--code", "--format", "json").out).asJsonObject
        assertEquals(emptyList<String>(), report["unused"].asJsonArray.map { it.asString })
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
        // Off: en-GB would inherit from en, and the plural forms of gb would not be compared.
        Files.writeString(project.resolve(SettingsFile.JSON_PATH), """{"localeAliases": "gb=en-GB", "regionalFallback": false}""")

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

    /** `en.json` and `fr.json` in [dir], holding [en] and [fr]; the check run on them with [args]. */
    private fun checkValues(dir: Path, en: String, fr: String, vararg args: String): Run {
        Files.writeString(dir.resolve("en.json"), en)
        Files.writeString(dir.resolve("fr.json"), fr)
        return check("--locales", dir.toString(), *args)
    }

    @Test
    fun `values - a placeholder missing or added is a problem`(@TempDir dir: Path) {
        val run = checkValues(
            dir,
            """{ "a": "Hi {name} {age}", "b": "Hi {name}" }""",
            """{ "a": "Salut {name}", "b": "Salut {name} {city}" }""",
        )
        assertEquals(I18nCheck.PROBLEMS, run.exit)
        assertTrue("fr: 2 bad values" in run.out, run.out)
        assertTrue("  value    a  Placeholder '{age}' of the reference locale is missing" in run.out, run.out)
        assertTrue("  value    b  Placeholder '{city}' is not in the reference locale" in run.out, run.out)
    }

    @Test
    fun `values - an unbalanced brace is one problem`(@TempDir dir: Path) {
        val run = checkValues(dir, """{ "a": "Hi {name}" }""", """{ "a": "Salut {name" }""")
        assertTrue("  value    a  Unbalanced braces" in run.out, run.out)
        assertTrue("1 problem found" in run.out, run.out)
    }

    @Test
    fun `values - tags that differ from the reference`(@TempDir dir: Path) {
        val run = checkValues(dir, """{ "a": "See <1>terms</1>" }""", """{ "a": "Voir <1>les CGU <b>ici</b>" }""")
        assertTrue("Tag '</1>' of the reference locale is missing" in run.out, run.out)
        assertTrue("Tag '</b>' is not in the reference locale" in run.out, run.out)
    }

    @Test
    fun `values - an ICU plural only needs its other form`(@TempDir dir: Path) {
        val noOther = "{n, plural, one {# item}}"
        val onlyOther = "{n, plural, other {# item}}"
        val run = checkValues(
            dir,
            """{ "a": "$noOther", "b": "$onlyOther", "c": "{n, plural, =0 {no items} other {# items}}" }""",
            """{ "a": "$noOther", "b": "$onlyOther", "c": "{n, plural, =0 {aucun élément} other {# éléments}}" }""",
        )
        assertTrue("  value    a  ICU 'plural' block without the required 'other' form" in run.out, run.out)
        assertTrue(run.out.lines().none { it.startsWith("  value    b") || it.startsWith("  value    c") }, run.out)
    }

    @Test
    fun `values - a multi-line ICU message is balanced`(@TempDir dir: Path) {
        val message = "{n, plural,\\n one {x}\\n other {y}\\n}"
        val run = checkValues(dir, """{ "a": "$message" }""", """{ "a": "$message" }""")
        assertEquals(I18nCheck.OK, run.exit, run.out)
    }

    @Test
    fun `values - a plural form may spell the count out`(@TempDir dir: Path) {
        val run = checkValues(
            dir,
            """{ "item_one": "One item", "item_other": "{{count}} items" }""",
            """{ "item_one": "{{count}} objet", "item_other": "{{count}} objets" }""",
        )
        assertEquals(I18nCheck.OK, run.exit, run.out)
    }

    @Test
    fun `values - JSON lists them per locale`(@TempDir dir: Path) {
        val run = checkValues(dir, """{ "a": "Hi {name} {age}" }""", """{ "a": "Salut {name}" }""", "--format", "json")
        val root = JsonParser.parseString(run.out).asJsonObject
        assertEquals(1, root["problems"].asInt)
        val fr = root["locales"].asJsonArray.map { it.asJsonObject }.single { it["locale"].asString == "fr" }
        val value = fr["values"].asJsonArray.single().asJsonObject
        assertEquals("a", value["key"].asString)
        assertEquals("Placeholder '{age}' of the reference locale is missing", value["message"].asString)
    }

    @Test
    fun `values - --no-values leaves them out of the report and of the exit code`(@TempDir dir: Path) {
        val run = checkValues(dir, """{ "a": "Hi {name} {age}" }""", """{ "a": "Salut {name}" }""", "--no-values")
        assertEquals(I18nCheck.OK, run.exit, run.out)
        assertTrue("Placeholder" !in run.out, run.out)
    }

    @Test
    fun `values - an ignored locale is left out`(@TempDir dir: Path) {
        Files.createDirectories(dir.resolve("locales"))
        Files.writeString(dir.resolve("i18n-check.json"), """{ "ignoredLocales": "fr" }""")
        Files.writeString(dir.resolve("locales/en.json"), """{ "a": "Hi {name} {age}" }""")
        Files.writeString(dir.resolve("locales/fr.json"), """{ "a": "Salut {name}" }""")
        assertEquals(I18nCheck.OK, check(dir.toString()).exit)
    }

    @Test
    fun `regional locale - inherits from its language, unless the settings turn it off`(@TempDir project: Path) {
        fun write(path: String, text: String) {
            val file = project.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        write("locales/en/common.json", """{"color": "color", "save": "Save"}""")
        write("locales/en-GB/common.json", """{"color": "colour"}""")
        write("locales/pt-BR/common.json", """{"color": "cor"}""")

        val inherited = check(project.toString(), "--locales", project.resolve("locales").toString())
        assertTrue("en-GB: no problem" in inherited.out, inherited.out)
        assertTrue("pt-BR: 1 missing" in inherited.out, inherited.out)

        write("i18n-check.json", """{"regionalFallback": false}""")
        val full = check(project.toString(), "--locales", project.resolve("locales").toString())
        assertTrue("en-GB: 1 missing" in full.out, full.out)
    }
}
