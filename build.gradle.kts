import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

fun properties(key: String) = providers.gradleProperty(key)

// Allow overriding platformVersion from CLI: ./gradlew ... -PplatformVersion=2025.1
val effectivePlatformVersion: String
    get() = providers.gradleProperty("platformVersion").orNull
        ?: properties("platformVersion").get()

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("org.jetbrains.changelog") version "2.5.0"
    id("jacoco")
}

group = properties("pluginGroup").get()
version = properties("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaUltimate(effectivePlatformVersion)
        bundledPlugin("JavaScript")
        bundledPlugin("org.jetbrains.plugins.yaml")
        // PHP plugin is not bundled in IntelliJ Ultimate 2025.3.3 (build 253.31033)
        plugin("com.jetbrains.php:253.31033.19")
        bundledPlugin("org.jetbrains.plugins.vue")
        // GNU GetText support — available from build 251.x onwards
        plugin("org.jetbrains.plugins.localization:253.28294.218")
        // Svelte is not bundled in IntelliJ Ultimate; without it a .svelte file is PLAIN_TEXT,
        // so no key inside one can be seen. Published by JetBrains, versioned on the platform.
        plugin("dev.blachut.svelte.lang:253.31033.19")
        // Svelte is not bundled in IntelliJ Ultimate; without it a .svelte file is PLAIN_TEXT,
        // so no key inside one can be seen. Published by JetBrains, versioned on the platform.
        // Svelte is not bundled in IntelliJ Ultimate; without it a .svelte file is PLAIN_TEXT,
        // so no key inside one can be seen. Published by JetBrains, versioned on the platform.

        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    // src/test/java/kotlinx/coroutines/debug/internal/DebugProbesImpl.java stubs
    // install$kotlinx_coroutines_core() missing from IU-253's bundled coroutines 1.8.0-intellij,
    // without breaking CancellableContinuation which changed binary signature in 1.9.0.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:6.1.3")
    testCompileOnly("org.junit.jupiter:junit-jupiter-api:6.1.3")
    testCompileOnly("org.junit.jupiter:junit-jupiter-params:6.1.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.1.3")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("com.jaliansystems:marathon-java-driver:5.4.0.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher") {
        because("Only needed to run tests in a version of IntelliJ IDEA that bundles older versions")
    }
    // junit-vintage-engine removed: all tests use JUnit 5 engine exclusively
}

// mockk pins kotlinx-coroutines 1.6.4 (too old) and kotlin-stdlib 2.0.0 (missing
// SequencesKt.sequenceOf(Object) added in Kotlin 2.2). IntelliJ 2025.3.4 bundles both in
// util-8.jar; the plugin sandbox must not shadow them with older versions from test deps.
//
// IGPP's prepareTestSandbox copies test-runtime JARs from intellijPlatformTestRuntimeClasspath
// into the plugin's sandbox lib/. Force kotlin-stdlib to 2.3.20 so the sandbox gets 2.3.20
// instead of 2.0.0. Coroutines are excluded so IntelliJ's bundled 1.9.x in util-8.jar wins.
configurations.named("testRuntimeClasspath") {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-debug")
}
configurations.named("intellijPlatformTestRuntimeClasspath") {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-debug")
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
    }
}

/**
 * The plugin descriptor caps `<change-notes>` at this many characters.
 *
 * Past it, `intellij-plugin-structure` raises `TooLongPropertyValue` at ERROR level, which
 * fails `verifyPluginStructure` — and therefore `buildPlugin` inside the release workflow,
 * after the tag has been pushed and before `publishPlugin` ever runs.
 *
 * The cap is nowhere in JetBrains' public documentation; it is hard-coded in
 * `PluginBeanValidator.validateChangeNotes`. 1.3.0 is the first release to reach it: its 101
 * entries render to 89 233 characters, where 1.2.1 shipped two, so nothing had exercised it.
 */
val changeNotesMaxLength = 65_535

/** Where the entries that do not fit can still be read in full. */
val changelogUrl = "https://github.com/IBRAHIMDANS/i18nSupportPlus/blob/main/CHANGELOG.md"

/**
 * [render] applied to as many of [entries] as the descriptor accepts, newest sections first.
 *
 * Entries are dropped from the end and the item re-rendered, rather than the HTML being cut at
 * a character offset: what ships is then always well-formed and always ends on a whole entry,
 * where a cut inside a `<li>` would reach the Plugins dialog as broken markup. Truncating is
 * preferred to selecting sections by hand because it is bounded by construction — a rule that
 * merely happens to fit today would re-cross the cap on some later release, silently.
 *
 * [render] is passed `null` for the unfiltered item, or the set of entries to keep.
 */
fun fitChangeNotes(entries: List<String>, render: (Set<String>?) -> String): String {
    val full = render(null)
    if (full.length <= changeNotesMaxLength) return full

    fun attempt(keep: Int): String =
        render(entries.take(keep).toSet()) + omittedEntriesNote(entries.size - keep)

    // Largest prefix of entries that still fits, note included.
    var low = 0
    var high = entries.size
    while (low < high) {
        val middle = (low + high + 1) / 2
        if (attempt(middle).length <= changeNotesMaxLength) low = middle else high = middle - 1
    }

    val fitted = attempt(low)
    // Two entries sharing their exact text are both kept by the filter, which can make a
    // rendering longer than the search measured. Falling back to none is degenerate but valid.
    return if (fitted.length <= changeNotesMaxLength) fitted else attempt(0)
}

fun omittedEntriesNote(dropped: Int): String =
    "<p><em>\u2026 and $dropped more entries, left out because the plugin descriptor caps this " +
        "field at $changeNotesMaxLength characters. The complete changelog is at " +
        "<a href=\"$changelogUrl\">$changelogUrl</a>.</em></p>"

intellijPlatform {
    pluginConfiguration {
        version = properties("pluginVersion")
        ideaVersion {
            sinceBuild = properties("pluginSinceBuild")
            untilBuild = properties("pluginUntilBuild")
        }

        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"
            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        changeNotes = changelog.run {
            val item = getOrNull(properties("pluginVersion").get()) ?: getLatest()
            fitChangeNotes(item.sections.values.flatten()) { keep ->
                renderItem(
                    if (keep == null) item else item.withFilter { entry -> entry in keep },
                    org.jetbrains.changelog.Changelog.OutputType.HTML
                )
            }
        }
    }

    publishing {
        token = System.getenv("IJ_HUB_TOKEN")
        channels = listOf(
            properties("pluginVersion").get().split('-').getOrElse(1) { "default" }.split('.').first()
        )
    }

    pluginVerification {
        // ide(type, version) removed in IGPP 2.14.0 — use recommended() or create()
        ignoredProblemsFile.set(file("verifier-ignored-problems.txt"))

        // The plugin's default is COMPATIBILITY_PROBLEMS + INTERNAL_API_USAGES +
        // OVERRIDE_ONLY_API_USAGES. INTERNAL_API_USAGES is dropped, and only that one.
        //
        // ToolWindowFactory declares getIcon(), getAnchor() and manage() with default bodies and
        // marks them @ApiStatus.Internal. I18nToolWindowFactory overrides none of them — it
        // implements createToolWindowContent() and nothing else, and the icon and anchor are read
        // from plugin.xml. Kotlin still emits an implementation for every interface member with a
        // default body, so the verifier sees all three both overridden and invoked, on 2025.1 and
        // 2025.2 (they are @Experimental from 2025.3 on, which is not a failure level here). There
        // is no source change that removes them, so the choice is to fail every release or to say
        // so here. Note that verifier-ignored-problems.txt cannot express this: it filters
        // compatibility problems, and an internal API usage is not one.
        failureLevel.set(listOf(FailureLevel.COMPATIBILITY_PROBLEMS, FailureLevel.OVERRIDE_ONLY_API_USAGES))
        ides {
            recommended()
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.1")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.2")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.3")
        }
    }
}

// ── runWebStorm ───────────────────────────────────────────────────────────────
//
// A second sandbox, running on a locally installed WebStorm, for the manual checks the
// IntelliJ IDEA Ultimate sandbox cannot host. That sandbox starts unlicensed, and an account
// that has already held an Ultimate licence is refused the trial outright — the log says
// `Trial dry-run: request was declined. Error code = EXISTING_LICENSE_IS_EXPIRED` — so the
// tool window cannot be opened there at all. WebStorm bundles the JavaScript support this
// plugin depends on, and it is the licence a JS developer already has.
//
// Opt-in and machine-local: pass `-PwebStormPath=/path/to/webstorm`, or set `webStormPath`
// in `~/.gradle/gradle.properties`. Nothing is registered when the property is absent or the
// path holds no `product-info.json`, so the build is byte-for-byte unchanged for everyone
// else, CI included. The main platform stays IntelliJ IDEA Ultimate: compilation, `test`,
// `buildPlugin` and `verifyPlugin` are untouched — this only adds a task.
//
// PHP and GNU GetText are not part of WebStorm, so that sandbox exercises everything except
// those two optional integrations. Both are declared `optional="true"` in plugin.xml.
val webStormPath: String? = providers.gradleProperty("webStormPath").orNull
if (webStormPath != null && File(webStormPath, "product-info.json").isFile) {
    intellijPlatformTesting.runIde.register("runWebStorm") {
        localPath = file(webStormPath)
    }
}

changelog {
    version = properties("pluginVersion")
    groups.set(emptyList())
}

tasks {
    register("printChangelogItem") {
        doLast {
            print(
                changelog.renderItem(
                    changelog.run {
                        getOrNull(properties("pluginVersion").get()) ?: getLatest()
                    },
                    org.jetbrains.changelog.Changelog.OutputType.MARKDOWN
                )
            )
        }
    }

    wrapper {
        gradleVersion = properties("gradleVersion").get()
    }

    jacocoTestReport {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    check {
        dependsOn(jacocoTestReport)
    }

    test {
        useJUnitPlatform {
            excludeEngines("junit-vintage")
        }
    }

    named("prepareTestSandbox") {
        doLast {
            val pluginName = properties("pluginName").get()
            val sandboxBase = layout.projectDirectory
                .dir(".intellijPlatform/sandbox/$pluginName/IU-$effectivePlatformVersion")
                .asFile

            // ~/.gradle/caches/<gradleVersion>/transforms/<hash>/transformed/idea-<version>/
            val transformsDir = File(gradle.gradleUserHomeDir, "caches/${gradle.gradleVersion}/transforms")
            val ideDir = transformsDir.listFiles()
                ?.mapNotNull { hashDir ->
                    File(hashDir, "transformed/idea-$effectivePlatformVersion")
                        .takeIf { it.isDirectory && File(it, "plugins").isDirectory }
                }
                ?.firstOrNull()

            // --- YAML: copy into plugins-test/ so ymlConfig.xml is activated ---
            //
            // Copied, not symlinked. The source lives in the shared Gradle transforms cache, and
            // a link to a *directory* there is a trap: deleting the sandbox descends through it
            // and empties plugins/yaml inside ~/.gradle. The next build then fails with
            // "Could not find bundled plugin with ID: org.jetbrains.plugins.yaml", or the whole
            // suite fails with NoClassDefFoundError: org/jetbrains/yaml/psi/YAMLKeyValue — for
            // every project sharing that cache, not just this one. It looks like a flake and is
            // not: it is this line. The plugin is 1.7 MB, so copying costs nothing measurable.
            ideDir?.let { ide ->
                val yamlSrc = File(ide, "plugins/yaml")
                val yamlDst = File(sandboxBase, "plugins-test/yaml")
                if (yamlSrc.exists() && !yamlDst.exists()) {
                    yamlSrc.copyRecursively(yamlDst, overwrite = true)
                }
            }

            // --- Vue: flatten lib/modules/ into lib/ so getPluginDistDirByClass() resolves ---
            //
            // These link individual *files*, which is why they never corrupted the cache the way
            // the YAML directory link did: deleting a link to a file removes the link, not the
            // target. Kept as links deliberately — the Vue jars are far larger than the YAML
            // plugin, and copying them on every sandbox build would be felt.
            ideDir?.let { ide ->
                val vueSrc = File(ide, "plugins/vuejs-plugin")
                val vueLibDst = File(sandboxBase, "plugins-test/vuejs-plugin/lib")
                if (vueSrc.exists() && !vueLibDst.exists()) {
                    vueLibDst.mkdirs()
                    File(vueSrc, "lib").listFiles { f -> f.isFile && f.extension == "jar" }
                        ?.forEach { jar ->
                            Files.createSymbolicLink(File(vueLibDst, jar.name).toPath(), jar.toPath())
                        }
                    File(vueSrc, "lib/modules").listFiles { f -> f.extension == "jar" }
                        ?.forEach { jar ->
                            Files.createSymbolicLink(File(vueLibDst, jar.name).toPath(), jar.toPath())
                        }
                }
            }

            // --- PHP: strip duplicate META-INF descriptors from php-frontback.jar ---
            val phpLib = File(sandboxBase, "plugins-test/php-impl/lib")
            val frontback = File(phpLib, "php-frontback.jar")
            if (frontback.exists()) {
                val patched = File(frontback.parent, "_php-frontback-patched.jar")
                ZipInputStream(frontback.inputStream().buffered()).use { zin ->
                    ZipOutputStream(patched.outputStream().buffered()).use { zout ->
                        var entry = zin.nextEntry
                        while (entry != null) {
                            val keep = !entry.name.startsWith("META-INF/") ||
                                       entry.name == "META-INF/MANIFEST.MF"
                            if (keep) {
                                zout.putNextEntry(ZipEntry(entry.name))
                                zin.copyTo(zout)
                                zout.closeEntry()
                            }
                            zin.closeEntry()
                            entry = zin.nextEntry
                        }
                    }
                }
                frontback.delete()
                patched.renameTo(frontback)
            }
            for (splitDir in listOf("frontend", "frontend-split")) {
                File(phpLib, splitDir).takeIf { it.exists() }?.deleteRecursively()
            }
        }
    }
}
