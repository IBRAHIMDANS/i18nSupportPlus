package com.ibrahimdans.i18n.extensions.technology.universe

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** How a `.meteor/packages` file declares universe:i18n. */
class UniverseI18nTest {

    @Test
    fun `a package line declares it, with or without a version`() {
        assertTrue(UniverseI18n.declaresPackage("meteor-base\nuniverse:i18n\n"))
        assertTrue(UniverseI18n.declaresPackage("meteor-base@1.5.1\nuniverse:i18n@2.0.0 # translations\n"))
        assertTrue(UniverseI18n.declaresPackage("  universe:i18n@=1.32.6  "))
    }

    @Test
    fun `a comment, another package or a longer name does not`() {
        assertFalse(UniverseI18n.declaresPackage("# universe:i18n\nmeteor-base\n"))
        assertFalse(UniverseI18n.declaresPackage("universe:i18n-blaze\n"))
        assertFalse(UniverseI18n.declaresPackage(""))
    }
}
