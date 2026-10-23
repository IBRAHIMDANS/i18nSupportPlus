// Source keys read with tree-sitter instead of the PSI, for tools running outside the IDE.
// The plugin never ships this module: its native libraries are of no use inside the IDE, which has
// the PSI, and they would weigh on every platform's ZIP.

plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    // The JNI binding, its native libraries bundled for Linux/macOS x64+arm64 and Windows x64,
    // and the TSX grammar, which parses JavaScript, TypeScript and JSX alike.
    implementation("io.github.bonede:tree-sitter:0.26.6")
    implementation("io.github.bonede:tree-sitter-tsx:0.23.2")

    // `core` declares its parsers compileOnly: the translation trees the tests resolve against need them.
    testImplementation("com.fasterxml.jackson.core:jackson-core:2.19.0")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}
