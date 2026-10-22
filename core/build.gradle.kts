// The part of the plugin that needs no IntelliJ Platform: no platform dependency is declared here,
// so a `com.intellij` import fails this module's compilation instead of slipping in unnoticed.
// The plugin ships it as lib/core.jar.

fun properties(key: String) = providers.gradleProperty(key)

plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    // Provided at runtime by the platform the plugin runs in: compileOnly, so the plugin ZIP
    // never ships a second copy that would clash with the platform's class loader.
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    compileOnly("com.google.code.gson:gson:2.11.0")
    // The translation file parsers: the platform loads both in its core class loader
    // (intellij.libraries.jackson and intellij.libraries.snakeyaml.engine, loading="embedded"),
    // at these versions from 2025.3 to 2026.2. Use no API newer than them.
    compileOnly("com.fasterxml.jackson.core:jackson-core:2.19.0")
    compileOnly("org.snakeyaml:snakeyaml-engine:2.9")

    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("com.fasterxml.jackson.core:jackson-core:2.19.0")
    testImplementation("org.snakeyaml:snakeyaml-engine:2.9")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    compilerOptions {
        // Same as the plugin: the platform's bundled Kotlin runtime is the one these classes run on.
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
    }
}

tasks {
    // The plugin reads its own version from this resource rather than asking the platform:
    // PluginManagerCore.getPlugin is @Internal from 2026.2 on, and every public replacement is
    // either internal as well or missing from 2025.1 — see PluginVersion.
    processResources {
        val pluginVersion = properties("pluginVersion")
        inputs.property("pluginVersion", pluginVersion)
        filesMatching("com/ibrahimdans/i18n/plugin-version.properties") {
            expand("pluginVersion" to pluginVersion.get())
        }
    }

    test {
        useJUnitPlatform()
    }
}
