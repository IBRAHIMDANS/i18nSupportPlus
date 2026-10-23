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
    // Same versions as `core` compiles against.
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.fasterxml.jackson.core:jackson-core:2.19.0")
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

application {
    applicationName = "i18n-check"
    mainClass.set("com.ibrahimdans.i18n.cli.MainKt")
}

tasks {
    test {
        useJUnitPlatform()
    }
}
