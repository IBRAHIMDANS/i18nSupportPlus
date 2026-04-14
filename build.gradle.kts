import org.jetbrains.changelog.markdownToHTML
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
        // PHP plugin is not bundled in IntelliJ Ultimate 2024.3.6 (build 243.26574)
        plugin("com.jetbrains.php:243.26574.100")
        // Vue.js plugin is bundled in IntelliJ Ultimate 2024.3.6
        bundledPlugin("org.jetbrains.plugins.vue")
        // GNU GetText (org.jetbrains.plugins.localization) has no version for 243.x builds
        // It is declared as optional in plugin.xml and requires no compile-time dependency

        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }

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

    signing {
        certificateChain = System.getenv("CERTIFICATE_CHAIN")
        privateKey = System.getenv("PRIVATE_KEY")
        password = System.getenv("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = System.getenv("IJ_HUB_TOKEN")
        channels = listOf(
            properties("pluginVersion").get().split('-').getOrElse(1) { "default" }.split('.').first()
        )
    }

    pluginVerification {
        // ide(type, version) was removed in IGPP 2.14.0 — use recommended() which
        // picks the current stable release, or local(path) for a specific install.
        ides {
            recommended()
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
    // 2. The PHP plugin 243.x ships split-mode modules (php-frontback.jar, frontend/)
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
