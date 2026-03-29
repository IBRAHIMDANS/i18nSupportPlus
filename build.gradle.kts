import org.jetbrains.changelog.markdownToHTML

fun properties(key: String) = providers.gradleProperty(key)

// Allow overriding platformVersion from CLI: ./gradlew ... -PplatformVersion=2025.1
val effectivePlatformVersion: String
    get() = providers.gradleProperty("platformVersion").orNull
        ?: properties("platformVersion").get()

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.3.0"
    id("org.jetbrains.changelog") version "2.2.1"
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
    testImplementation("com.jaliansystems:marathon-java-driver:5.2.5.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher") {
        because("Only needed to run tests in a version of IntelliJ IDEA that bundles older versions")
    }
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:6.0.3")
}

// Set the JVM language level used to compile sources and generate files - Java 21 required since IntelliJ 2024.3
kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
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
        // Verify the built plugin against all supported IDE versions.
        // These targets are independent of the build-time platformVersion.
        ides {
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaUltimate, "2024.3")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaUltimate, "2025.1")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaUltimate, "2025.2")
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
        useJUnitPlatform()
    }

    // The PHP plugin 243.x ships split-mode modules (php-frontback.jar, frontend/)
    // that duplicate extension points already declared in php.jar, causing
    // PluginException in tests. Remove them after sandbox preparation.
    named("prepareTestSandbox") {
        doLast {
            val phpLib = layout.buildDirectory.dir("idea-sandbox/IU-$effectivePlatformVersion/plugins-test/php-impl/lib")
            val frontback = phpLib.get().file("php-frontback.jar").asFile
            val frontend = phpLib.get().dir("frontend").asFile
            if (frontback.exists()) frontback.delete()
            if (frontend.exists()) frontend.deleteRecursively()
        }
    }
}
