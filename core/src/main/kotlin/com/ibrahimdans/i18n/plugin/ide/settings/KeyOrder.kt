package com.ibrahimdans.i18n.plugin.ide.settings

/**
 * Order the plugin keeps translation keys in: when *Sort i18n Keys* or `sortKeysAlphabetically`
 * sorts a whole file, and when `extractSorted` inserts a new key among the existing ones. Both
 * go through the same [comparator], so a key inserted in order is still in order once the file
 * is sorted.
 *
 * Settings store the entry by its name: an entry is never renamed, new ones are only added.
 */
enum class KeyOrder(val comparator: Comparator<String>) {

    /** `apple`, `Banana`, `cherry`: case is ignored. The plugin's order before the setting existed. */
    IGNORE_CASE(compareBy { it.lowercase() }),

    /**
     * `Banana`, `apple`, `cherry`: character codes, every upper-case letter before any
     * lower-case one. The order of the IDE's *Sort properties alphabetically* (`String.compareTo`).
     */
    CHARACTER_CODE(naturalOrder()),

    /**
     * `item1`, `Item2`, `item10`: case is ignored and a run of digits is read as a number.
     * See [NaturalKeyComparator] for how ties are broken.
     */
    NATURAL(NaturalKeyComparator);
}

/**
 * Natural order of keys: `item2` before `item10`, case ignored.
 *
 * Keys are compared in three steps, each one only when the previous ones tie, which keeps the
 * order total and consistent (antisymmetric, transitive, `0` only for equal strings):
 * 1. Character by character ignoring case, except that a run of ASCII digits is compared as a
 *    number: its length once the leading zeros are stripped, then its digits. A number sorts
 *    where its first digit would among the other characters (`a-` < `a1` < `a_`), and a key
 *    sorts before the longer keys it starts (`item` < `item1`). Numbers are never parsed, so a
 *    run of any length works.
 * 2. Fewer leading zeros first, at the first number where they differ: `a1` < `a01` < `a001`.
 * 3. Character codes (`String.compareTo`): `Apple` < `apple`.
 */
internal object NaturalKeyComparator : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        var leadingZeros = 0
        while (i < a.length && j < b.length) {
            if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
                val endA = digitRunEnd(a, i)
                val endB = digitRunEnd(b, j)
                val startA = significantStart(a, i, endA)
                val startB = significantStart(b, j, endB)
                val byLength = (endA - startA).compareTo(endB - startB)
                if (byLength != 0) return byLength
                for (k in 0 until endA - startA) {
                    val byDigit = a[startA + k].compareTo(b[startB + k])
                    if (byDigit != 0) return byDigit
                }
                if (leadingZeros == 0) leadingZeros = (startA - i).compareTo(startB - j)
                i = endA
                j = endB
            } else {
                val byChar = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        val byRest = (a.length - i).compareTo(b.length - j)
        if (byRest != 0) return byRest
        if (leadingZeros != 0) return leadingZeros
        return a.compareTo(b)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private fun digitRunEnd(s: String, from: Int): Int {
        var end = from
        while (end < s.length && s[end].isAsciiDigit()) end++
        return end
    }

    private fun significantStart(s: String, from: Int, end: Int): Int {
        var start = from
        while (start < end && s[start] == '0') start++
        return start
    }
}
