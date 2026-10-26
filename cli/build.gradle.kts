// `i18n-check`: the translation files of a project checked from a terminal or a CI job, without
// IntelliJ. The plugin never ships this module.

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    // `--code`: the key usages of the i18next code, read with tree-sitter.
    implementation(project(":core-treesitter"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    // `core` declares them compileOnly, the IDE providing them: outside it, the CLI ships them.
    // Same versions as `core` compiles against, except jackson-core: the IDE's 2.19.0 has DoS fixes
    // only from 2.21.7 / 2.22.3 on (GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89), and the CLI ships its own.
    // `core` keeps compiling against 2.19.0, so it uses no API newer than what it runs on here.
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.fasterxml.jackson.core:jackson-core:2.22.4")
    implementation("org.snakeyaml:snakeyaml-engine:2.9")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    compilerOptions {
        // Same as `core`, whose classes it runs.
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
    }
}

// Released with the plugin, under its version: `i18n-check-<version>.zip` is attached to each GitHub release.
version = providers.gradleProperty("pluginVersion").get()

application {
    applicationName = "i18n-check"
    mainClass.set("com.ibrahimdans.i18n.cli.MainKt")
}

tasks {
    test {
        useJUnitPlatform()
    }
}
