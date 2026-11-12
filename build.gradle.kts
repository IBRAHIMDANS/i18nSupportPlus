import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

fun properties(key: String) = providers.gradleProperty(key)

// Allow overriding platformVersion from CLI: ./gradlew ... -PplatformVersion=2025.2
val effectivePlatformVersion: String
    get() = providers.gradleProperty("platformVersion").orNull
        ?: properties("platformVersion").get()

// The platform branch of that version: 2025.3.4 -> 253, 2026.2.4 -> 262. The plugins IntelliJ Ultimate does not
// bundle are published once per branch, and 2026.2 moved a few platform classes out of the core classpath.
val platformBranch: Int = effectivePlatformVersion.split('.').let { (year, release) -> "${year.takeLast(2)}$release".toInt() }

// Non-bundled plugins, by branch. A branch absent from the map takes the 253 builds (the default platform's),
// which is what an older -PplatformVersion has always got.
val unbundledPlugins: Map<String, String> = mapOf(
    253 to mapOf(
        "com.jetbrains.php" to "253.31033.19",
        "org.jetbrains.plugins.localization" to "253.28294.218",
        "dev.blachut.svelte.lang" to "253.31033.19",
        "com.jetbrains.php.blade" to "253.28294.325",
        "org.jetbrains.plugins.astro" to "253.32098.37",
        "com.jetbrains.twig" to "253.28294.322",
    ),
    262 to mapOf(
        "com.jetbrains.php" to "262.11879.100",
        "org.jetbrains.plugins.localization" to "262.8665.173",
        "dev.blachut.svelte.lang" to "262.10968.63",
        "com.jetbrains.php.blade" to "262.8665.176",
        "org.jetbrains.plugins.astro" to "262.10968.63",
        "com.jetbrains.twig" to "262.8665.173",
    ),
).let { byBranch -> byBranch[platformBranch] ?: byBranch.getValue(253) }

fun unbundled(id: String) = "$id:${unbundledPlugins.getValue(id)}"

// Kept independent from the compilation platform: screenshots must come from one pinned product
// build, otherwise font and UI changes make the README images churn on every IDE update.
val uiTestWebStormVersion = "2025.3.4"
// Starter/Driver artifacts follow IntelliJ IDEA builds, not WebStorm's (253.32098.39 is not published).
val uiTestStarterVersion = "253.32098.37"

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
    id("org.jetbrains.changelog") version "2.5.0"
    id("org.gradle.test-retry") version "1.6.6"
    // 2.0 is the first line built on Kotlin 2.4: 1.23 parses with Kotlin 2.0 and refuses to run beside 2.4.
    id("dev.detekt") version "2.0.0-alpha.6"
    id("jacoco")
}

// Static analysis of the four modules, against config/detekt/detekt.yml only. What the code already breaks is
// frozen per module in config/detekt/baseline-<module>-main.xml: `detektMain` fails on new findings only.
// Regenerate a baseline with `detektBaselineMain` after fixing entries, never to hide a new finding.
allprojects {
    apply(plugin = "dev.detekt")
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig.set(false)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        val module = if (this@allprojects == rootProject) "plugin" else this@allprojects.name
        baseline.set(rootProject.file("config/detekt/baseline-$module.xml"))
    }
    // The plain `detekt` task reads no types, so UnsafeCallOnNullableType never runs, and it has no baseline
    // of its own: `check` runs the analysis the CI runs instead.
    // Hooked once Kotlin is applied: a module's build script applies it after this block runs.
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        tasks.named("detekt") { enabled = false }
        tasks.named("check") { dependsOn("detektMain") }
    }
}

group = properties("pluginGroup").get()
version = properties("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

sourceSets {
    create("uiTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val uiTestImplementation by configurations.getting {
    extendsFrom(configurations.implementation.get())
}
val uiTestRuntimeOnly by configurations.getting

dependencies {
    intellijPlatform {
        intellijIdeaUltimate(effectivePlatformVersion)
        bundledPlugin("JavaScript")
        bundledPlugin("org.jetbrains.plugins.yaml")
        // Java resource bundles (.properties), declared in propertiesConfig.xml only
        bundledPlugin("com.intellij.properties")
        // PHP plugin is not bundled in IntelliJ Ultimate 2025.3.3 (build 253.31033)
        plugin(unbundled("com.jetbrains.php"))
        bundledPlugin("org.jetbrains.plugins.vue")
        // Git: Compare Translations with Branch, declared in gitConfig.xml only
        bundledPlugin("Git4Idea")
        if (platformBranch >= 262) {
            // 2026.2 moved these out of the core classpath: the supertypes of GitRepository and
            // GitRepositoryManager into two product modules, JBCefApp into the JCEF plugin's UI module.
            bundledModule("intellij.platform.vcs.dvcs")
            bundledModule("intellij.platform.vcs.dvcs.impl")
            bundledPlugin("com.intellij.modules.jcef")
            bundledModule("intellij.platform.ui.jcef")
            // Without it every test fails on "Cannot create extension (class=…) [Plugin: com.intellij.modules.ultimate]":
            // an interface of product-backend.jar shadows the Ultimate plugin's post-startup activity of the same
            // obfuscated name on the shared test classpath. The workaround JetBrains gives in
            // intellij-platform-gradle-plugin#2261, until a fix ships.
            testBundledPlugins("com.intellij.modules.ultimate")
        }
        // MCP server: the translation tools for AI agents, declared in mcpConfig.xml only
        bundledPlugin("com.intellij.mcpServer")
        // GNU GetText support — available from build 251.x onwards
        plugin(unbundled("org.jetbrains.plugins.localization"))
        // Svelte is not bundled in IntelliJ Ultimate; without it a .svelte file is PLAIN_TEXT,
        // so no key inside one can be seen. Published by JetBrains, versioned on the platform.
        plugin(unbundled("dev.blachut.svelte.lang"))
        // Blade is not bundled in IntelliJ Ultimate either. Only for the tests and runIde: the plugin
        // declares no dependency on it, a Blade echo being PHP the Blade plugin injects.
        plugin(unbundled("com.jetbrains.php.blade"))
        // Astro is not bundled in IntelliJ Ultimate either: without it a .astro file is PLAIN_TEXT.
        // Only for the tests and runIde (AstroCallsTest): the plugin declares no dependency on it.
        plugin(unbundled("org.jetbrains.plugins.astro"))
        // Twig is bundled in PhpStorm only: Symfony's `'key'|trans` and `{% trans %}`, declared in twigConfig.xml.
        plugin(unbundled("com.jetbrains.twig"))

        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    implementation(project(":core"))
    // The tree-sitter extractors `i18n-check` runs, for the tests comparing them with the PSI ones
    // (ExtractorParityTest). Tests only: the plugin never ships them.
    testImplementation(project(":core-treesitter"))

    // src/test/java/kotlinx/coroutines/debug/internal/DebugProbesImpl.java stubs
    // install$kotlinx_coroutines_core() missing from IU-253's bundled coroutines 1.8.0-intellij,
    // without breaking CancellableContinuation which changed binary signature in 1.9.0.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:6.1.3")
    testCompileOnly("org.junit.jupiter:junit-jupiter-api:6.1.3")
    testCompileOnly("org.junit.jupiter:junit-jupiter-params:6.1.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.1.3")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher") {
        because("Only needed to run tests in a version of IntelliJ IDEA that bundles older versions")
    }

    // Starter controls a complete IDE from a second JVM. Keep its JUnit 5 stack separate from the
    // platform fixture's JUnit 6 dependencies used by src/test.
    intellijPlatform {
        testFramework(
            TestFrameworkType.Starter,
            uiTestStarterVersion,
            configurationName = "uiTestImplementation"
        )
    }
    // The UI test runs in its own JVM, not inside the IDE, so it needs a stdlib of its own:
    // without it Starter's transitive 2.1.20 meets kotlin-reflect 2.2.20 (NoSuchFieldError CONTEXT).
    uiTestImplementation(kotlin("stdlib"))
    uiTestImplementation("org.junit.jupiter:junit-jupiter-api:5.13.4")
    uiTestRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.4")
    uiTestRuntimeOnly("org.junit.platform:junit-platform-launcher")
    uiTestImplementation("org.kodein.di:kodein-di-jvm:7.26.1")
    uiTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1")
    // junit-vintage-engine removed: all tests use JUnit 5 engine exclusively
}

// mockk pins kotlinx-coroutines 1.6.4 (too old) and kotlin-stdlib 2.0.0 (missing
// SequencesKt.sequenceOf(Object) added in Kotlin 2.2). IntelliJ 2025.3.4 bundles both in
// util-8.jar; the plugin sandbox must not shadow them with older versions from test deps.
//
// IGPP's prepareTestSandbox copies test-runtime JARs from intellijPlatformTestRuntimeClasspath
// into the plugin's sandbox lib/. Force kotlin-stdlib to the build's own version so the sandbox
// does not get mockk's 2.0.0. Coroutines are excluded so IntelliJ's bundled 1.9.x in util-8.jar wins.
// Introduced by #32 (2026-04-14) and #56 (2026-04-23, platform 253). Recheck when mockk is bumped
// or when platformVersion moves to a new branch: drop both blocks and run any coroutine test.
configurations.named("testRuntimeClasspath") {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-debug")
}
configurations.named("intellijPlatformTestRuntimeClasspath") {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
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
        // The floor is 2025.2 (pluginSinceBuild = 252): on 2025.1 the Marketplace verifier resolves the optional
        // com.intellij.mcpServer dependency to the old standalone MCP Server plugin (same id, other API), reports
        // the MCP classes as a compatibility problem and blocks the release; it ignores verifier-ignored-problems.txt.
        // ide(type, version) removed in IGPP 2.14.0 — use recommended() or create()
        ignoredProblemsFile.set(file("verifier-ignored-problems.txt"))

        // The plugin's default is COMPATIBILITY_PROBLEMS + INTERNAL_API_USAGES +
        // OVERRIDE_ONLY_API_USAGES. INTERNAL_API_USAGES is dropped, and only that one.
        //
        // ToolWindowFactory declares getIcon(), getAnchor() and manage() with default bodies and
        // marks them @ApiStatus.Internal. I18nToolWindowFactory overrides none of them — it
        // implements createToolWindowContent() and nothing else, and the icon and anchor are read
        // from plugin.xml. Kotlin still emits an implementation for every interface member with a
        // default body, so the verifier sees all three both overridden and invoked, on 2025.2
        // (they are @Experimental from 2025.3 on, which is not a failure level here). There
        // is no source change that removes them, so the choice is to fail every release or to say
        // so here. Note that verifier-ignored-problems.txt cannot express this: it filters
        // compatibility problems, and an internal API usage is not one.
        //
        // Every other internal API usage still has to fail: the Marketplace refuses the upload
        // (1.18.0, FileSizeLimit). The Plugin Compatibility workflow reads the reports after this
        // task and fails on any internal API usage but those three.
        failureLevel.set(listOf(FailureLevel.COMPATIBILITY_PROBLEMS, FailureLevel.OVERRIDE_ONLY_API_USAGES))
        ides {
            // recommended() only lists releases of the target platform's own type (IU, from productInfo) within
            // pluginSinceBuild..pluginUntilBuild: it never adds another product.
            recommended()
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.2")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.3")
            // The two IDEs the README targets first, named so that recommended() leaving them out cannot go unnoticed.
            create(IntelliJPlatformType.WebStorm, "2025.3")
            create(IntelliJPlatformType.PhpStorm, "2025.3")
            // No JavaScript plugin: every class plugin.xml registers must load without it (the dependency is
            // optional). The last Community release; 2025.3 and later are the unified IntelliJ IDEA.
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6.4")
            // Android Studio has no JavaScript nor YAML plugin either, and its own version numbers: Panda 4
            // (2025.3.4.6, Release) sits on platform 253.32098.37, the reference IntelliJ IDEA 2025.3.4's.
            // To move it, pick a Release of the wanted platform in https://jb.gg/android-studio-releases-list.xml.
            create(IntelliJPlatformType.AndroidStudio, "2025.3.4.6")
        }
    }
}

// ── runIdeCommunity ───────────────────────────────────────────────────────────
//
// The plugin as an IDE without the JavaScript plugin loads it — IntelliJ IDEA Community, Android
// Studio, PyCharm, an unlicensed IntelliJ IDEA 2025.3+: the dependency is optional and jsConfig.xml
// stays out, so the tool window, the translation files' inspections, machine translation and the
// agent tools are all there is. Compiled against IntelliJ IDEA Ultimate as always; only the sandbox
// changes. 2025.2.6.4 is the last Community release: 2025.3 and later are the unified IntelliJ IDEA,
// Community being that IDEA without a licence.
intellijPlatformTesting.runIde.register("runIdeCommunity") {
    type = IntelliJPlatformType.IntellijIdeaCommunity
    version = "2025.2.6.4"
    // -PideProject=examples/react-multi-namespace opens that project at once, for a smoke check under Xvfb.
    task { providers.gradleProperty("ideProject").orNull?.let { args(file(it).absolutePath) } }
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
//
// The plugins it installs follow the local WebStorm, not `platformVersion`: the ones pinned above
// are built for the compilation branch, and a WebStorm of another branch refuses them (Astro and
// Svelte for 253 in a WebStorm 263). The sandbox therefore takes none of them, only Astro and
// Svelte in the version Marketplace lists as compatible with that WebStorm. PHP, Blade and Twig
// do not install in WebStorm.
val webStormPath: String? = providers.gradleProperty("webStormPath").orNull
if (webStormPath != null && File(webStormPath, "product-info.json").isFile) {
    intellijPlatformTesting.runIde.register("runWebStorm") {
        localPath = file(webStormPath)
        plugins {
            compatiblePlugins("org.jetbrains.plugins.astro", "dev.blachut.svelte.lang")
        }
    }
    configurations.named("intellijPlatformTestPluginDependency_runWebStorm") { setExtendsFrom(emptySet()) }
} else if (webStormPath != null) {
    // Given but unusable (often a path Toolbox moved): say so, instead of "Task 'runWebStorm' not found".
    logger.warn("runWebStorm is not registered: webStormPath=$webStormPath holds no product-info.json")
}

// ── WebStorm UI tests: README screenshots and verified flows ─────────────────
//
// These tasks deliberately sit outside `check`: each starts a licensed WebStorm and takes minutes
// on a cold cache. They share the uiTest source set; each task runs its own test class.
fun registerUiTest(
    taskName: String,
    taskDescription: String,
    testClass: String,
    ideType: IntelliJPlatformType = IntelliJPlatformType.WebStorm,
    ideVersion: String = uiTestWebStormVersion,
    projectSource: String = "examples/react-multi-namespace",
    configure: Test.() -> Unit,
) {
    intellijPlatformTesting.testIdeUi.register(taskName) {
        type = ideType
        version = ideVersion
        testFramework(TestFrameworkType.Starter, uiTestStarterVersion)
        task {
            val uiTestSourceSet = sourceSets.named("uiTest")
            description = taskDescription
            testClassesDirs = uiTestSourceSet.get().output.classesDirs
            classpath = uiTestSourceSet.get().runtimeClasspath
            dependsOn(tasks.buildPlugin)
            useJUnitPlatform()
            filter { includeTestsMatching(testClass) }
            maxParallelForks = 1
            jvmArgs("--add-opens=java.base/sun.nio.fs=ALL-UNNAMED")
            // The capture reads the Xvfb screen directly. Without this, a WAYLAND_DISPLAY in the
            // environment sends java.awt.Robot through the screencast portal, which refuses.
            systemProperty("awt.robot.screenshotMethod", "x11")
            systemProperty("i18n.ui.screenshots.ideVersion", uiTestWebStormVersion)
            systemProperty("i18n.ui.screenshots.projectSource", layout.projectDirectory.dir(projectSource).asFile.absolutePath)
            testLogging { events("passed", "skipped", "failed") }
            configure()
        }
    }
}

// task name to (test mode, description): both run the same scenario, only the verdict differs.
mapOf(
    "generateUiScreenshots" to ("record" to "Regenerates the approved README UI screenshots in WebStorm."),
    "verifyUiScreenshots" to ("verify" to "Verifies the approved README UI screenshots in WebStorm."),
).forEach { (taskName, modeAndDescription) ->
    val (mode, taskDescription) = modeAndDescription
    registerUiTest(taskName, taskDescription, "*.ReadmeScreenshotsTest") {
        systemProperty("i18n.ui.screenshots.mode", mode)
        systemProperty("i18n.ui.screenshots.baselineDirectory", layout.projectDirectory.dir("docs/img").asFile.absolutePath)
        // -PuiScreenshots=a.png,b.png takes only those images, to iterate without taking them all.
        systemProperty("i18n.ui.screenshots.only", providers.gradleProperty("uiScreenshots").getOrElse(""))
        systemProperty("i18n.ui.screenshots.output", layout.buildDirectory.dir("ui-screenshots/$mode").get().asFile.absolutePath)
    }
}

registerUiTest(
    "verifyUiFlows",
    "Plays the setup wizard, Show Translations Inline and the settings page in WebStorm and checks their effect.",
    "*.VerifiedFlowsTest",
) {
    systemProperty("i18n.ui.flows.output", layout.buildDirectory.dir("ui-flows").get().asFile.absolutePath)
}

// IntelliJ IDEA Community needs no license, so this one runs in CI (Plugin Compatibility). 2025.2.6.4 is the
// last Community release the IntelliJ repository publishes (no 2025.3.x); later Community users run the
// unified IntelliJ IDEA without a licence, where the JavaScript plugin is off just the same.
val uiTestCommunityVersion = "2025.2.6.4"
registerUiTest(
    "verifyCommunityFlows",
    "Opens examples/simple in IntelliJ IDEA Community, without the JavaScript plugin, and checks the tool window shows its keys.",
    "*.CommunityFlowsTest",
    ideType = IntelliJPlatformType.IntellijIdeaCommunity,
    ideVersion = uiTestCommunityVersion,
    projectSource = "examples/simple",
) {
    systemProperty("i18n.ui.community.ideVersion", uiTestCommunityVersion)
    systemProperty("i18n.ui.flows.output", layout.buildDirectory.dir("ui-flows-community").get().asFile.absolutePath)
}

changelog {
    version = properties("pluginVersion")
    groups.set(emptyList())
}

// ── What's New page ───────────────────────────────────────────────────────────
//
// Every released version of CHANGELOG.md, rendered to HTML exactly as <change-notes> is, and
// embedded in the jar for the in-IDE "What's New" page (WhatsNewPage). Each version starts with
// a `<!-- whats-new-version: X.Y.Z -->` marker line, newest first, as in the file. Unreleased
// entries are left out: they belong to no version a user can be running. Not capped like
// <change-notes>: the descriptor limit does not apply to a resource.
val whatsNewHtml: String = changelog.run {
    val released = Regex("""\d+\.\d+.*""")
    getAll().values
        .filter { released.matches(it.version) }
        .joinToString("\n") { item ->
            "<!-- whats-new-version: ${item.version} -->\n" +
                renderItem(item, org.jetbrains.changelog.Changelog.OutputType.HTML)
        }
}

val generateWhatsNew = tasks.register("generateWhatsNew") {
    val html = whatsNewHtml
    val output = layout.buildDirectory.dir("generated/whatsNew")
    inputs.property("html", html)
    outputs.dir(output)
    doLast {
        output.get().file("com/ibrahimdans/i18n/whats-new.html").asFile.apply {
            parentFile.mkdirs()
            writeText(html)
        }
    }
}

sourceSets.main {
    resources.srcDir(generateWhatsNew)
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

        // The platform pings api.jetbrains.ai when the test project opens, if the developer is
        // signed in to a JetBrains Account (never in CI): the thread still waiting on the answer
        // when the first test ends fails it as a leaked thread. TRACE data collection is the only
        // caller of that ping, and a system property overrides its registry key.
        systemProperty("trace.data.collection.enabled", "false")

        // -Pi18nBenchmark=true runs UsageCountBenchmarkTest at full size: it prints its timings and
        // writes them to build/reports/usage-benchmark.txt. Without it the test only checks its counts.
        systemProperty("i18n.benchmark", providers.gradleProperty("i18nBenchmark").getOrElse("false"))
        systemProperty("i18n.benchmark.output", layout.buildDirectory.file("reports/usage-benchmark.txt").get().asFile.absolutePath)

        // CI splits the plugin's suite across runners: -PtestShard=2/3 runs the second third. The
        // fixture cannot fork, so shards are separate machines, not parallel workers. A test class
        // goes to a shard by a stable hash of its top-level name (nested classes follow it), so
        // every class — a new one included — lands in exactly one shard, with no list to maintain.
        providers.gradleProperty("testShard").orNull?.let { spec ->
            val parts = spec.split('/').mapNotNull { it.trim().toIntOrNull() }
            require(parts.size == 2 && parts[1] >= 1 && parts[0] in 1..parts[1]) {
                "testShard must read i/n with 1 <= i <= n, got '$spec'"
            }
            val (index, count) = parts
            exclude { element ->
                if (element.isDirectory || !element.name.endsWith(".class")) return@exclude false
                val topLevel = element.relativePath.pathString.removeSuffix(".class").substringBefore('$')
                Math.floorMod(topLevel.hashCode(), count) != index - 1
            }
        }

        // A net for platform flakes, not for our own bugs. The IntelliJ test fixture occasionally
        // trips over its own teardown — a listener already disposed, an editor already closed —
        // and takes down whichever test was running at the time. A single retry separates that
        // from a real defect: a real defect fails twice. Off locally, so a flake you introduce
        // while writing a test stays visible instead of being quietly retried away. A test that
        // passes on retry leaves the build green: the CI's "Summarise the shards" step counts it
        // and names it in a warning, so it is seen without failing the run.
        retry {
            maxRetries = if (providers.environmentVariable("CI").isPresent) 1 else 0
            failOnPassedAfterRetry = false
        }

        // The platform's class loaders define plugin classes without a code source, and JaCoCo
        // skips such classes by default: the report then shows 0 % on every file. jdk.internal
        // stays out, instrumenting it breaks the JVM.
        extensions.configure<JacocoTaskExtension> {
            isIncludeNoLocationClasses = true
            excludes = listOf("jdk.internal.*")
        }
    }

    // Three patches of the test sandbox, none of which changes what ships. Each one says what breaks
    // without it, why, where it comes from, and when to try removing it. No YouTrack issue was ever
    // cited for any of them. "Recheck" means: delete the block, run the tests named, keep it if they fail.
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
            // Without it: -Dplugin.path lists only this plugin and php-impl, the bundled YAML plugin is not
            // loaded, ymlConfig.xml is never activated, and every YAML test fails (keys not found).
            // Cause: the test sandbox only loads the plugins under plugins-test/; YAML is bundled with the IDE
            // but not declared as a test plugin there.
            // Origin: #20 (2026-03-30, a symlink), turned into a copy by #184 (2026-08-22, see below).
            // Recheck: at each platformVersion branch change or IGPP major bump, with YamlScalarValuesTest.
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
            // Cause, as recorded when it was added: from platform 253 the Vue plugin keeps part of its jars in
            // lib/modules/, and PathManager.getPluginDistDirByClass() only finds a plugin's directory from a jar
            // sitting directly in its lib/; the Vue plugin then cannot locate its own files in the test sandbox.
            // The exact failure was not written down at the time.
            // Origin: #56 (2026-04-23, upgrade to platform 253).
            // Recheck: at each platformVersion branch change, with the Vue tests (FoldingTestVue, *Vue* under runVue).
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
            //
            // Without it: PluginException on a duplicate extension point registration, com.jetbrains.php does not
            // load, phpConfig.xml is never activated, and every PHP test fails.
            // Cause: the PHP plugin ships split-mode modules (php-frontback.jar, frontend/, frontend-split/) whose
            // META-INF descriptors declare again extension points php.jar already declares. Only the descriptors
            // go: deleting the whole jar also removed classes the PHP plugin needs, so it still did not load.
            // Origin: #20 (2026-03-30, PHP plugin 243.x); frontend-split/ added by #56 (2026-04-23, platform 253).
            // Recheck: at each platformVersion branch change or PHP plugin bump, with PhpGettextHighlightingTest.
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
