package com.ibrahimdans.i18n.plugin

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the one thing no other test can: that a test actually runs.
 *
 * `build.gradle.kts` configures `useJUnitPlatform { excludeEngines("junit-vintage") }`. The vintage
 * engine is what discovers `test*` methods by the JUnit 3 convention — the convention
 * `BasePlatformTestCase` was written for — so with it disabled **only** Jupiter annotations are
 * discovered. A `fun testSomething()` carrying no annotation compiles, reads like a test, and never
 * runs. Nothing reports it: it is absent from the results rather than failing in them.
 *
 * That is how four classes went silent — `TranslationToCodeTestBase` (JSON translation→code),
 * `ReferenceTestPhpGettext`, `CodeCompletionTestBase` and `CreateMissingTranslationsTest`, 118 cases
 * between them — while the suite stayed green. It is the same family as the silent skip removed in
 * #179 and the empty `@Test` methods removed in #180, and the third time it has reached the repo,
 * which is why it is worth a test of its own rather than another round of cleanup.
 *
 * Only declarations that *look* like a case are checked: a `private` helper named `testTree()` or a
 * top-level `testLocalization()` builds fixtures and is none of this test's business.
 */
class SuiteIntegrityTest {

    private val jupiterAnnotations = listOf(
        "@Test", "@ParameterizedTest", "@RepeatedTest", "@TestFactory", "@TestTemplate",
        // Fully qualified forms appear in the suite too, e.g. SetupWizardDialogTest.
        "@org.junit.jupiter.api.Test",
    )

    /** A method declaration that would be a case if it were annotated: not private, named `test…`. */
    private val candidate = Regex("""^ +(?:internal +)?fun +(test\w*)\s*\(""")

    /** Any member declaration, used to bound the annotation block that precedes a candidate. */
    private val member = Regex("""^ +(?:private +|internal +|protected +|override +)*(fun|val|var|class|object) """)

    @Test
    fun everyTestMethodCarriesAnAnnotationThatMakesItRun() {
        val unannotated = mutableListOf<String>()

        File("src/test/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val lines = file.readLines()
                var previousMember = 0
                lines.forEachIndexed { index, line ->
                    val name = candidate.find(line)?.groupValues?.get(1)
                    if (name == null) {
                        if (member.containsMatchIn(line)) previousMember = index
                        return@forEachIndexed
                    }
                    val preceding = lines.subList(previousMember, index).joinToString("\n")
                    if (jupiterAnnotations.none { preceding.contains(it) }) {
                        unannotated += "${file.path}:${index + 1} $name"
                    }
                    previousMember = index
                }
            }

        assertTrue(
            unannotated.isEmpty(),
            "These methods are named like test cases but carry no JUnit 5 annotation, so the " +
                    "platform never runs them — they are absent from the report rather than failing " +
                    "in it. Annotate them, or rename them if they are helpers:\n" +
                    unannotated.joinToString("\n")
        )
    }
}
