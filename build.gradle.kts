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

// Configure project's dependencies
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
        // Vue.js plugin is bundled in IntelliJ Ultimate 2025.3.3
        bundledPlugin("org.jetbrains.plugins.vue")
        // GNU GetText support — available from build 251.x onwards
        plugin("org.jetbrains.plugins.localization:253.28294.218")

        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

    // Kotlin 2.3.x stdlib calls DebugProbesImpl.install$kotlinx_coroutines_core() at startup.
    // This method was added in core:1.9.0 but is absent from the 1.8.0-intellij version
    // bundled with IU-2025.3.3. We CANNOT use core:1.9.0 entirely because CancellableContinuation
    // changed tryResume from (T,Any?,Function1) to (T,Any?,Function3), breaking IntelliJ's
    // internal binary code compiled against 1.8.0-intellij.
    //
    // Solution: src/test/java/kotlinx/coroutines/debug/internal/DebugProbesImpl.java is a
    // compatibility stub that satisfies both:
    //   - the legacy AgentPremain (getEnableCreationStackTraces, install, etc.)
    //   - the Kotlin 2.3.x stdlib (install$kotlinx_coroutines_core)
    // The test classloader loads test sources before bundled platform jars, so this stub
    // shadows the bundled DebugProbesImpl without touching CancellableContinuation.

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
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    // junit-vintage-engine removed: all IntelliJ platform tests now use JUnit 5 engine exclusively
}

// Exclude kotlinx-coroutines-core:1.9.0 that transitive dependencies (mockk BOM, etc.) pull in.
// IntelliJ 2025.3.3 bundles kotlinx-coroutines-core 1.8.0-intellij on its platform classpath;
// 1.9.0 renames several internal APIs (SelectKt.access$getSTATE_REG$p, CancellableContinuation
// .tryResume signature, etc.) that IntelliJ's binary code compiled against 1.8.0-intellij still
// calls → NoSuchMethodError deep in IntelliJ's coroutine-based startup sequence → test hang.
//
// The missing install$kotlinx_coroutines_core() is provided by the DebugProbesImpl stub in
// src/test/java/kotlinx/coroutines/debug/internal/DebugProbesImpl.java, which is compiled into
// the test jar and loaded before the bundled platform jar by IntelliJ's PathClassLoader.
configurations.named("testRuntimeClasspath") {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-debug")
}

// Set the JVM language level used to compile sources and generate files - Java 21 required since IntelliJ 2024.3
kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
    }
}

// Configure Gradle IntelliJ Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
intellijPlatform {
    pluginConfiguration {
        version = properties("pluginVersion")
        ideaVersion {
            sinceBuild = properties("pluginSinceBuild")
            untilBuild = properties("pluginUntilBuild")
        }

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
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
        // Verify the built plugin against supported IDE versions.
        // ide(type, version) was removed in IGPP 2.14.0 — use recommended() which
        // picks the current stable release, or local(path) for a specific install.
        ides {
            recommended()
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.1")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.2")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.3")
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    version = properties("pluginVersion")
    groups.set(emptyList())
}

tasks {
    wrapper {
        gradleVersion = properties("gradleVersion").get()
    }

    jacocoTestReport {
        reports {
            xml.required.set(true)
            html.required.set(false)
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

    // After sandbox preparation:
    // 1. Symlink the bundled YAML plugin into plugins-test/ so IntelliJ loads it
    //    and activates our optional ymlConfig.xml. Without this, -Dplugin.path only
    //    lists our plugin and php-impl, leaving YAML unloaded and all YAML tests failing.
    // 2. The PHP plugin 253.x ships split-mode modules (php-frontback.jar, frontend/)
    //    whose META-INF descriptors duplicate extension points already declared in php.jar,
    //    causing PluginException. Strip only the META-INF descriptors from php-frontback.jar
    //    (keep the class files) so the PHP plugin loads cleanly as com.jetbrains.php,
    //    activating our optional phpConfig.xml.
    named("prepareTestSandbox") {
        doLast {
            // --- YAML: symlink bundled plugin from sandbox IDE into plugins-test ---
            // IGPP v2 extracts the full IDE to idea-sandbox/IU-xxx/plugins/.
            // YAML is bundled but not in plugins-test/, so its optional extensions
            // (ymlConfig.xml) are never activated during tests. Symlinking it in
            // plugins-test/ forces IntelliJ to load it as a regular plugin.
            val sandboxBase = layout.buildDirectory
                .dir("idea-sandbox/IU-$effectivePlatformVersion")
                .get().asFile
            val yamlSrc = File(sandboxBase, "plugins/yaml")
            val yamlDst = File(sandboxBase, "plugins-test/yaml")
            if (yamlSrc.exists() && !yamlDst.exists()) {
                Files.createSymbolicLink(yamlDst.toPath(), yamlSrc.toPath())
            }

            // --- Localization: marketplace plugin, lands in plugins-test/ automatically — no patch needed ---

            // --- Vue: flatten lib/modules/*.jar into lib/ to fix getPluginDistDirByClass ---
            // In 2025.3+, bundled plugins use lib/modules/ for module JARs. The Vue plugin's
            // LSP service (VueServicesKt.<clinit>) calls PluginPathManager.getPluginResource()
            // which relies on getPluginDistDirByClass() expecting JARs directly in lib/.
            // Moving module JARs to lib/ restores the expected layout without removing Vue support.
            val vueModulesDir = File(sandboxBase, "plugins/vuejs-plugin/lib/modules")
            if (vueModulesDir.isDirectory) {
                val vueLibDir = vueModulesDir.parentFile
                vueModulesDir.listFiles { f -> f.extension == "jar" }?.forEach { jar ->
                    jar.renameTo(File(vueLibDir, jar.name))
                }
                vueModulesDir.delete()
            }

            // --- PHP: strip META-INF descriptors from php-frontback.jar, keep classes ---
            val phpLib = layout.buildDirectory.dir("idea-sandbox/IU-$effectivePlatformVersion/plugins-test/php-impl/lib")
            val frontback = phpLib.get().file("php-frontback.jar").asFile
            if (frontback.exists()) {
                val patched = File(frontback.parent, "_php-frontback-patched.jar")
                ZipInputStream(frontback.inputStream().buffered()).use { zin ->
                    ZipOutputStream(patched.outputStream().buffered()).use { zout ->
                        var entry = zin.nextEntry
                        while (entry != null) {
                            // Keep MANIFEST.MF and all class/resource files; drop plugin descriptors
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
            val frontend = phpLib.get().dir("frontend").asFile
            if (frontend.exists()) frontend.deleteRecursively()
        }
    }
}
