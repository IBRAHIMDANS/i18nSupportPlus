package com.ibrahimdans.i18n.extract.laravel

import com.ibrahimdans.i18n.extract.rules.ruleExtractor
import com.ibrahimdans.i18n.plugin.parser.CodeKeyExtractor
import com.ibrahimdans.i18n.plugin.parser.CodeNode
import com.ibrahimdans.i18n.plugin.parser.RawKey
import com.ibrahimdans.i18n.plugin.rules.CallRule
import com.ibrahimdans.i18n.plugin.rules.FrameworkRules
import com.ibrahimdans.i18n.plugin.utils.KeyElement

/**
 * The keys of the `__('key')` calls [LaravelSource] writes, each spelled as `i18n-check` loads
 * Laravel's files: `lang/en/messages.php` holds the namespace `messages`, so the code's
 * `messages.auth.failed` is read as `messages` + [nsSeparator] + `auth.failed`. A JSON phrase —
 * `Log in`, `Saved.`, `Hello, :name` — is left as written: no file name leads it. One with an empty
 * level once cut at [keySeparator], `Saved.`, is read unsplit, as the plugin reads it.
 */
class LaravelKeyExtractor(private val nsSeparator: String, private val keySeparator: String) : CodeKeyExtractor {

    private val calls = ruleExtractor(RULES)

    override fun canExtract(node: CodeNode): Boolean = calls.canExtract(node)

    override fun extract(node: CodeNode): RawKey? {
        val key = calls.extract(node) ?: return null
        val text = key.keyElements.singleOrNull()?.text ?: return key
        val group = GROUP_KEY.matchEntire(text)?.takeUnless { nsSeparator.isEmpty() || nsSeparator in text }
        if (group == null) {
            val emptyLevel = keySeparator.isNotEmpty() && keySeparator in text && text.split(keySeparator).any { it.isEmpty() }
            return if (emptyLevel) key.copy(unsplit = true) else key
        }
        return key.copy(keyElements = listOf(KeyElement.literal(group.groupValues[1] + nsSeparator + group.groupValues[2])))
    }

    private companion object {
        val RULES = FrameworkRules(id = "laravel", calls = listOf(CallRule(LaravelSource.CALL)))

        /** A file's key: the file name, then a non-empty path without spaces. */
        val GROUP_KEY = Regex("""([\w-]+)\.(\S+)""")
    }
}

/** Laravel's calls, read on the code [LaravelSource] gives a PHP file or a Blade view. */
fun laravelExtractors(nsSeparator: String, keySeparator: String): List<CodeKeyExtractor> = listOf(LaravelKeyExtractor(nsSeparator, keySeparator))
