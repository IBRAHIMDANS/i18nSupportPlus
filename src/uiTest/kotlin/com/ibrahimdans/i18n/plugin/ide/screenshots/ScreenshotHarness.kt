package com.ibrahimdans.i18n.plugin.ide.screenshots

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.closeAllNotifications
import com.intellij.driver.sdk.closeAndDisableAllBalloonNotifications
import com.intellij.driver.sdk.ui.boundsOnScreen
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.components.common.IdeaFrameUI
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import java.nio.file.LinkOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolute
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration.Companion.minutes

/**
 * One README image: [take] drives the IDE to the state the image shows and returns the screen
 * bounds to capture; [cleanUp] closes what it opened, so the next screenshot of the session starts
 * from a known state. Screenshots of a session run in list order, which is part of that state.
 */
class Screenshot(
    val fileName: String,
    /** Set when the image shows a hover (tooltip, hint): the pointer then stays where [take] left it. */
    val keepPointer: Boolean = false,
    val cleanUp: ScreenshotScope.() -> Unit = {},
    val take: ScreenshotScope.() -> Rectangle,
)

/** What a screenshot drives: the IDE through [driver], and its project window through [frame]. */
class ScreenshotScope(val driver: Driver, val frame: IdeaFrameUI) {

    /**
     * Waits for a rendered text (list rows and table cells are not components) in the frame or in
     * a popup, which the IDE may open as a window of its own when it does not fit in the frame.
     */
    fun waitForText(description: String, predicate: (String) -> Boolean) {
        waitFor(description, 1.minutes) {
            (listOf<UiComponent>(frame) + driver.ui.xx("//div[@class='HeavyWeightWindow']").list())
                .any { window -> window.getAllTexts().any { predicate(it.text) } }
        }
    }
}

/**
 * A group of screenshots taken in one WebStorm session on one project. [pluginSettings] are the
 * `i18nSettings` options written into the project copy; null writes none, as on a project the
 * plugin has never configured (the setup wizard's case).
 */
class ScreenshotSession(
    val name: String,
    val screenshots: List<Screenshot>,
    val pluginSettings: Map<String, String>? = REFERENCE_SETTINGS,
) {
    companion object {
        /** What the manual recipe had configured on examples/react-multi-namespace. */
        val REFERENCE_SETTINGS = mapOf(
            "defaultNs" to "common",
            "foldingMaxLength" to "60",
            "partialTranslationInspectionEnabled" to "true",
            "preferredLocalization" to "json",
            "previewLocale" to "en",
            "searchInProjectOnly" to "false",
            "translationsRoot" to "public/locales",
        )
    }
}

object ScreenshotHarness {

    // Smaller than the 1920x1440 Xvfb screen on purpose: the strip below the frame belongs to no
    // window, so a pointer parked there hovers nothing and raises no tooltip.
    val FRAME_BOUNDS = Rectangle(0, 0, 1920, 1400)
    private val POINTER_PARKING = Point(960, 1420)
    private const val STABLE_CAPTURE_ATTEMPTS = 20
    private const val STABLE_CAPTURE_INTERVAL_MS = 500

    private val ideVersion: String = System.getProperty("i18n.ui.screenshots.ideVersion")
    val projectSource: Path = Path.of(System.getProperty("i18n.ui.screenshots.projectSource"))
    // Not .idea: the example ignores it, so it exists only on the machine that last opened it.
    private val REFERENCE_PROJECT_ENTRIES =
        listOf("package.json", "public", "src", "tsconfig.json", "tsconfig.app.json", "tsconfig.node.json")

    /**
     * Runs [session] in a fresh WebStorm and writes its images (filtered by [selected]) into
     * [outputDirectory]. A failing screenshot does not stop the others: the failures come back, one
     * message each, so one run shows everything that needs fixing.
     */
    fun run(session: ScreenshotSession, runName: String, outputDirectory: Path, selected: (Screenshot) -> Boolean): List<String> {
        val screenshots = session.screenshots.filter(selected)
        if (screenshots.isEmpty()) return emptyList()
        val failures = mutableListOf<String>()
        val project = copyReferenceProject(outputDirectory.resolve("projects").resolve("${session.name}-$runName"))
        session.pluginSettings?.let { writePluginSettings(project, it) }

        Starter.newContext(
            "screenshots-${session.name}-$runName",
            TestCase(IdeProductProvider.WS, LocalProjectInfo(project)).withVersion(ideVersion)
        ).apply {
            applyVMOptionsPatch {
                addSystemProperty("idea.trust.all.projects", "true")
                addSystemProperty("ide.show.tips.on.startup.default.value", "false")
                addSystemProperty("jb.consents.confirmation.enabled", "false")
                addSystemProperty("jb.privacy.policy.text", "<!--999.999-->")
                addSystemProperty("user.language", "en")
                addSystemProperty("user.country", "US")
                addSystemProperty("sun.java2d.uiScale", "1")
            }
            setLicense(licenseKey())
            PluginConfigurator(this).installPluginFromPath(Path.of(System.getProperty("path.to.build.plugin")))
        }.runIdeWithDriver().useDriverAndCloseIde {
            waitForIndicators(5.minutes)
            // Balloons open over the bottom-right corner, where the tool window is docked; the
            // copied project has no node_modules, so "Install dependencies" is sure to show.
            closeAndDisableAllBalloonNotifications()
            stopCaretBlinking()
            ideFrame {
                // Without a window manager under Xvfb the frame keeps its default size, which
                // would make every capture depend on the IDE build rather than on the test.
                setBounds(FRAME_BOUNDS)
                waitFor("the IDE frame to take its fixed bounds", 1.minutes) { boundsOnScreen == FRAME_BOUNDS }
                val scope = ScreenshotScope(this@useDriverAndCloseIde, this)
                for (screenshot in screenshots) {
                    try {
                        // WebStorm opens its evaluation prompt once a session, on a timer: a scenario
                        // it interrupts gets a second try once the prompt is closed.
                        val attempt = {
                            dismissIdePrompts()
                            val bounds = screenshot.take(scope)
                            // Some balloons show up after indexing.
                            closeAllNotifications()
                            capture(bounds, outputDirectory.resolve(runName).resolve(screenshot.fileName), screenshot.keepPointer)
                        }
                        runCatching(attempt).onFailure {
                            runCatching { screenshot.cleanUp(scope) }
                            attempt()
                        }
                    } catch (e: Throwable) {
                        // The whole screen as it was, to see what the scenario ran into.
                        val failure = outputDirectory.resolve(runName).resolve("failed-${screenshot.fileName}")
                        Files.createDirectories(failure.parent)
                        ImageIO.write(Robot().createScreenCapture(Rectangle(0, 0, 1920, 1440)), "png", failure.toFile())
                        val cause = generateSequence(e) { it.cause }.last()
                        failures += "${screenshot.fileName} ($runName): ${cause.message?.lineSequence()?.firstOrNull()}, screen in $failure"
                    } finally {
                        runCatching { screenshot.cleanUp(scope) }
                    }
                }
            }
        }
        return failures
    }

    /** WebStorm's own prompts, which open on a timer of their own and would sit over a capture. */
    private fun Driver.dismissIdePrompts() {
        for (dialog in ui.xx("//div[@class='MyDialog']").list()) {
            if (dialog.hasText("Evaluation Feedback")) dialog.x("//div[@class='JButton' and @visible_text='No Thanks']").click()
        }
    }

    private fun licenseKey(): String = requireNotNull(System.getenv("LICENSE_KEY")?.takeIf(String::isNotBlank)) {
        "LICENSE_KEY is required to run licensed WebStorm UI screenshots"
    }

    @OptIn(ExperimentalPathApi::class)
    private fun copyReferenceProject(target: Path): Path {
        val normalized = target.absolute().normalize()
        // Path.deleteRecursively removes the node_modules link without following it; File's
        // deleteRecursively walked into it and emptied the example's own node_modules.
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) target.deleteRecursively()
        Files.createDirectories(normalized)
        for (entry in REFERENCE_PROJECT_ENTRIES) {
            val source = projectSource.resolve(entry).toFile()
            check(source.exists()) { "Reference project is missing $entry" }
            check(source.copyRecursively(normalized.resolve(entry).toFile(), overwrite = true)) {
                "Could not copy $entry into the screenshot project"
            }
        }
        // Without its dependencies TypeScript underlines every JSX line and WebStorm asks to install
        // them: the copy shares the example's node_modules (installed with npm install --no-package-lock).
        val dependencies = projectSource.resolve("node_modules")
        // An empty folder passes for installed: look for a package the example imports.
        check(Files.isDirectory(dependencies.resolve("react-i18next"))) {
            "Run 'npm install --no-package-lock' in $projectSource first: the editor screenshots need its node_modules"
        }
        Files.createSymbolicLink(normalized.resolve("node_modules"), dependencies)
        return normalized
    }

    private fun writePluginSettings(project: Path, options: Map<String, String>) {
        val lines = options.entries.joinToString("\n") { (name, value) -> """    <option name="$name" value="$value" />""" }
        val xml = """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<project version="4">
            |  <component name="i18nSettings">
            |$lines
            |  </component>
            |</project>
            """.trimMargin()
        Files.createDirectories(project.resolve(".idea"))
        Files.writeString(project.resolve(".idea/i18nSettings.xml"), xml)
    }

    private fun capture(bounds: Rectangle, destination: Path, keepPointer: Boolean) {
        check(bounds.width > 0 && bounds.height > 0) { "Nothing visible to capture for ${destination.fileName}" }
        Files.createDirectories(destination.parent)
        val robot = Robot()
        // Driver clicks leave the pointer over what they clicked: hover highlight and tooltip.
        if (!keepPointer) robot.mouseMove(POINTER_PARKING.x, POINTER_PARKING.y)
        robot.waitForIdle()
        check(ImageIO.write(stableCapture(robot, bounds), "png", destination.toFile()))
    }

    /** Waits for the area to stop changing (tooltip fading out, repaint after the pointer left). */
    private fun stableCapture(robot: Robot, bounds: Rectangle): BufferedImage {
        var previous = robot.createScreenCapture(bounds)
        repeat(STABLE_CAPTURE_ATTEMPTS) {
            robot.delay(STABLE_CAPTURE_INTERVAL_MS)
            val current = robot.createScreenCapture(bounds)
            if (firstDifference(previous, current) == null) return current
            previous = current
        }
        error("The captured area kept changing for ${STABLE_CAPTURE_ATTEMPTS * STABLE_CAPTURE_INTERVAL_MS} ms")
    }

    /** The first differing pixel, or a size mismatch, as a message; null when both images are identical. */
    fun firstDifference(a: BufferedImage, b: BufferedImage): String? {
        if (a.width != b.width || a.height != b.height) return "${a.width}x${a.height} vs ${b.width}x${b.height}"
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) return "pixel ($x, $y)"
            }
        }
        return null
    }
}

/**
 * The component of [type] on the row of the label [label] inside this container: forms built with
 * the Kotlin UI DSL do not tie a field to its label, but they put both on one row.
 */
fun UiComponent.fieldRightOf(label: String): UiComponent =
    fieldRightOf(label, "javax.swing.JTextField", UiComponent::class.java)

fun <T : UiComponent> UiComponent.fieldRightOf(label: String, type: String, component: Class<T>): T {
    val origin = boundsOnScreen
    val labelPoint = getAllTexts().first { it.text == label }.point
    val labelY = origin.y + labelPoint.y
    val labelX = origin.x + labelPoint.x
    return xx(component) { byType(type) }.list()
        .filter { field -> field.boundsOnScreen.let { labelY in it.y..(it.y + it.height) && it.x > labelX } }
        .minBy { it.boundsOnScreen.x }
}

/** Replaces the text of a field as a user would: select all, type over it. */
fun UiComponent.typeOver(text: String) {
    click()
    keyboard {
        hotKey(java.awt.event.KeyEvent.VK_CONTROL, java.awt.event.KeyEvent.VK_A)
        typeText(text)
    }
}
