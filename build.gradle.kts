import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
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
    id("org.jetbrains.kotlin.jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.14.0"
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

        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    // src/test/java/kotlinx/coroutines/debug/internal/DebugProbesImpl.java stubs
    // install$kotlinx_coroutines_core() missing from IU-253's bundled coroutines 1.8.0-intellij,
    // without breaking CancellableContinuation which changed binary signature in 1.9.0.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testCompileOnly("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testCompileOnly("org.junit.jupiter:junit-jupiter-params:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("com.jaliansystems:marathon-java-driver:5.4.0.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
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
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-debug")
}
configurations.named("intellijPlatformTestRuntimeClasspath") {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
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

        changeNotes = changelog.renderItem(
            changelog.run {
                getOrNull(properties("pluginVersion").get()) ?: getLatest()
            },
            org.jetbrains.changelog.Changelog.OutputType.HTML
        )
    }

    publishing {
        token = System.getenv("IJ_HUB_TOKEN")
        channels = listOf(
            properties("pluginVersion").get().split('-').getOrElse(1) { "default" }.split('.').first()
        )
    }

    pluginVerification {
        // ide(type, version) removed in IGPP 2.14.0 — use recommended() or create()
        ides {
            recommended()
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.1")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.2")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.3")
        }
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

            // --- YAML: symlink into plugins-test/ so ymlConfig.xml is activated ---
            ideDir?.let { ide ->
                val yamlSrc = File(ide, "plugins/yaml")
                val yamlDst = File(sandboxBase, "plugins-test/yaml")
                if (yamlSrc.exists() && !yamlDst.exists()) {
                    Files.createSymbolicLink(yamlDst.toPath(), yamlSrc.toPath())
                }
            }

            // --- Vue: flatten lib/modules/ into lib/ so getPluginDistDirByClass() resolves ---
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
