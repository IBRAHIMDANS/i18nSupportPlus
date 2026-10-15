package com.ibrahimdans.i18n.plugin.ide.settings

/**
 * Order the plugin keeps translation keys in: when *Sort i18n Keys* or `sortKeysAlphabetically`
 * sorts a whole file, and when `extractSorted` inserts a new key among the existing ones. Both
 * go through the same [comparator], so a key inserted in order is still in order once the file
 * is sorted.
 */
enum class KeyOrder(val comparator: Comparator<String>) {

    /** `apple`, `Banana`, `cherry`: case is ignored. The plugin's order before the setting existed. */
    IGNORE_CASE(compareBy { it.lowercase() }),

    /**
     * `Banana`, `apple`, `cherry`: character codes, every upper-case letter before any
     * lower-case one. The order of the IDE's *Sort properties alphabetically* (`String.compareTo`).
     */
    CHARACTER_CODE(naturalOrder());
}
