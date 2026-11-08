package com.ibrahimdans.i18n.plugin.usages

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

/**
 * The inverted index the IDE's usage count and `i18n-check` share, tested on its own: a site is
 * filed under its path and every path above it, and a file's sites are replaced or dropped without
 * touching another file's.
 */
class CallSiteIndexTest {

    private data class Site(override val path: List<String>, val line: Int = 0) : CallSiteIndex.Site

    /** A site equal only to itself, as the readers' sites are: two files never share one. */
    private class OwnSite(override val path: List<String>) : CallSiteIndex.Site

    private fun site(key: String, line: Int = 0) = Site(if (key.isEmpty()) emptyList() else key.split('.'), line)

    @Test
    fun `a site is filed under its path and every path above it`() {
        val index = CallSiteIndex<Site>()
        val home = site("menu.home")
        index.update("a.ts", listOf(home))

        Assertions.assertEquals(setOf(home), index.under(listOf("menu")))
        Assertions.assertEquals(setOf(home), index.under(listOf("menu", "home")))
        Assertions.assertEquals(emptyList<Site>(), index.at(listOf("menu")))
        Assertions.assertEquals(listOf(home), index.at(listOf("menu", "home")))
        Assertions.assertEquals(emptySet<Site>(), index.under(listOf("home")))
        Assertions.assertEquals(emptySet<Site>(), index.under(listOf("menu", "home", "label")))
    }

    @Test
    fun `a site with an empty path is kept with its file under no path`() {
        val index = CallSiteIndex<Site>()
        val built = site("")
        index.update("a.ts", listOf(built))

        Assertions.assertEquals(listOf(built), index.sites().toList())
        Assertions.assertEquals(setOf("a.ts"), index.paths)
        Assertions.assertEquals(emptySet<Site>(), index.under(emptyList()))
    }

    @Test
    fun `updating a file replaces its sites and leaves another file's under a shared prefix`() {
        val index = CallSiteIndex<Site>()
        val homeInA = site("menu.home", line = 1)
        val aboutInB = site("menu.about", line = 2)
        index.update("a.ts", listOf(homeInA))
        index.update("b.ts", listOf(aboutInB))

        val contactInA = site("menu.contact", line = 3)
        index.update("a.ts", listOf(contactInA))

        Assertions.assertEquals(setOf(aboutInB, contactInA), index.under(listOf("menu")))
        Assertions.assertEquals(emptySet<Site>(), index.under(listOf("menu", "home")))
        Assertions.assertEquals(setOf(aboutInB), index.under(listOf("menu", "about")))
        Assertions.assertEquals(setOf(aboutInB, contactInA), index.sites().toSet())
    }

    @Test
    fun `removing a file empties the buckets it alone filled and keeps the other files`() {
        val index = CallSiteIndex<Site>()
        val homeInA = site("menu.home", line = 1)
        val titleInA = site("page.title", line = 2)
        val aboutInB = site("menu.about", line = 3)
        index.update("a.ts", listOf(homeInA, titleInA))
        index.update("b.ts", listOf(aboutInB))

        index.remove("a.ts")

        Assertions.assertEquals(setOf("b.ts"), index.paths)
        Assertions.assertEquals(setOf(aboutInB), index.under(listOf("menu")))
        Assertions.assertEquals(emptySet<Site>(), index.under(listOf("menu", "home")))
        Assertions.assertEquals(emptySet<Site>(), index.under(listOf("page")))
        Assertions.assertEquals(emptySet<Site>(), index.under(listOf("page", "title")))
        Assertions.assertEquals(listOf(aboutInB), index.sites().toList())
    }

    @Test
    fun `removing a file with the same paths as another keeps the other's sites`() {
        val index = CallSiteIndex<OwnSite>()
        val inA = OwnSite(listOf("menu", "home"))
        val inB = OwnSite(listOf("menu", "home"))
        index.update("a.ts", listOf(inA))
        index.update("b.ts", listOf(inB))

        index.remove("a.ts")

        Assertions.assertEquals(setOf(inB), index.under(listOf("menu")))
        Assertions.assertEquals(listOf(inB), index.at(listOf("menu", "home")))
    }

    @Test
    fun `removing a file never indexed changes nothing`() {
        val index = CallSiteIndex<Site>()
        val home = site("menu.home")
        index.update("a.ts", listOf(home))

        index.remove("unknown.ts")

        Assertions.assertEquals(setOf("a.ts"), index.paths)
        Assertions.assertEquals(setOf(home), index.under(listOf("menu")))
        Assertions.assertEquals(listOf(home), index.sites().toList())
    }

    @Test
    fun `clear drops every file and every path`() {
        val index = CallSiteIndex<Site>()
        index.update("a.ts", listOf(site("menu.home"), site("")))
        index.update("b.ts", listOf(site("page.title")))

        index.clear()

        Assertions.assertEquals(emptySet<String>(), index.paths)
        Assertions.assertEquals(emptyList<Site>(), index.sites().toList())
        Assertions.assertEquals(emptySet<Site>(), index.under(listOf("menu")))
        Assertions.assertEquals(emptySet<Site>(), index.under(listOf("page", "title")))
    }

    @Test
    fun `two equal sites of one file count once under a path`() {
        val index = CallSiteIndex<Site>()
        val home = site("menu.home")
        index.update("a.ts", listOf(home, home.copy()))

        Assertions.assertEquals(1, index.under(listOf("menu")).size)
        Assertions.assertEquals(1, index.at(listOf("menu", "home")).size)
    }
}
