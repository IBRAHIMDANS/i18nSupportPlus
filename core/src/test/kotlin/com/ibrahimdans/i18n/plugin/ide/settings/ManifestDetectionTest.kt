package com.ibrahimdans.i18n.plugin.ide.settings

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The manifests the setup wizard reads from disk, on temporary folders: no platform, as [WizardPlanTest].
 */
class ManifestDetectionTest {

    @TempDir
    lateinit var base: File

    private fun write(path: String, text: String) {
        File(base, path).apply { parentFile.mkdirs() }.writeText(text)
    }

    // -- Frameworks declared at a folder

    @Test
    fun `frameworks come from every manifest of the folder`() {
        write("package.json", """{"dependencies": {"vue-i18n": "10"}}""")
        write("composer.json", """{"require": {"laravel/framework": "11"}}""")

        Assertions.assertEquals(setOf("vue-i18n", "laravel"), ManifestDetection.detectFrameworksIn(base))
    }

    @Test
    fun `a manifest below the folder is not read for it`() {
        write("apps/web/package.json", """{"dependencies": {"i18next": "23"}}""")

        Assertions.assertEquals(emptySet<String>(), ManifestDetection.detectFrameworksIn(base))
    }

    // -- Manifests between the root and the catalogues

    @Test
    fun `the manifests above each catalogue are found, the root's left out`() {
        write("package.json", """{"dependencies": {"i18next": "23"}}""")
        write("apps/shop/package.json", """{"dependencies": {"react-i18next": "17"}}""")
        write("apps/shop/src/package.json", """{"name": "nested"}""")
        write("apps/api/composer.json", """{"require": {"laravel/framework": "11"}}""")
        File(base, "apps/shop/src/locales/en").mkdirs()
        File(base, "apps/api/lang").mkdirs()

        val manifests = ManifestDetection.manifestsAbove(base, listOf("apps/shop/src/locales", "apps\\api\\lang"))

        Assertions.assertEquals(setOf("apps/shop", "apps/shop/src", "apps/api"), manifests.keys)
        Assertions.assertEquals(setOf("laravel"), FrameworkDetector.detect(manifests.getValue("apps/api")))
    }

    @Test
    fun `two manifests of one folder are read together`() {
        write("apps/api/composer.json", """{"require": {"laravel/framework": "11"}}""")
        write("apps/api/package.json", """{"dependencies": {"vue-i18n": "10"}}""")

        val manifests = ManifestDetection.manifestsAbove(base, listOf("apps/api/lang"))

        Assertions.assertEquals(setOf("laravel", "vue-i18n"), FrameworkDetector.detect(manifests.getValue("apps/api")))
    }

    // -- Workspace packages

    private fun monorepo() {
        write("packages/i18n/package.json", """{"name": "@acme/i18n"}""")
        write("apps/web/package.json", """{"name": "web"}""")
        write("apps/admin/package.json", """{"name": "admin"}""")
        write("apps/web/node_modules/@acme/i18n/package.json", """{"name": "@acme/i18n"}""")
        write("apps/web/test/fixture/package.json", """{"name": "fixture"}""")
        write(".cache/pkg/package.json", """{"name": "cached"}""")
    }

    @Test
    fun `a pnpm monorepo lists its packages, exclusions, comments and node_modules aside`() {
        monorepo()
        write(
            "pnpm-workspace.yaml",
            "# workspace\npackages:\n  - 'apps/*'   # applications\n\n  - \"packages/*\"\n  - '!**/test/**'\ncatalog:\n  - 'other/*'\n"
        )

        Assertions.assertEquals(
            setOf("apps/web", "apps/admin", "packages/i18n"),
            ManifestDetection.workspacePackagesIn(base).keys
        )
        Assertions.assertEquals("""{"name": "web"}""", ManifestDetection.workspacePackagesIn(base).getValue("apps/web"))
    }

    @Test
    fun `npm workspace globs match nested folders`() {
        monorepo()
        write("package.json", """{"workspaces": ["apps/**", "!apps/web/test/**"]}""")

        Assertions.assertEquals(setOf("apps/web", "apps/admin"), ManifestDetection.workspacePackagesIn(base).keys)
    }

    @Test
    fun `Yarn's packages object is read like npm's array`() {
        monorepo()
        write("package.json", """{"workspaces": {"packages": ["packages/*"]}}""")

        Assertions.assertEquals(setOf("packages/i18n"), ManifestDetection.workspacePackagesIn(base).keys)
    }

    @Test
    fun `pnpm-workspace yaml wins over the workspaces of package json`() {
        monorepo()
        write("pnpm-workspace.yaml", "packages:\n  - 'packages/*'\n")
        write("package.json", """{"workspaces": ["apps/*"]}""")

        Assertions.assertEquals(setOf("packages/i18n"), ManifestDetection.workspacePackagesIn(base).keys)
    }

    @Test
    fun `no workspace, or an unreadable package json, lists nothing`() {
        monorepo()
        Assertions.assertEquals(emptyMap<String, String>(), ManifestDetection.workspacePackagesIn(base))

        write("package.json", """{"workspaces": ["apps/*" """)
        Assertions.assertEquals(emptyMap<String, String>(), ManifestDetection.workspacePackagesIn(base))

        write("package.json", """{"workspaces": "apps/*"}""")
        Assertions.assertEquals(emptyMap<String, String>(), ManifestDetection.workspacePackagesIn(base))
    }
}
