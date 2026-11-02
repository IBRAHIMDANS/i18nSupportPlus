package com.ibrahimdans.i18n.extract.laravel

import com.ibrahimdans.i18n.extract.i18next.findKeys
import com.ibrahimdans.i18n.extract.treesitter.TreeSitterCodeNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.File

class LaravelSourceTest {

    private val root = File("..").canonicalFile

    /** `line: key` for each key of [text], read as `i18n-check` reads a PHP file or a Blade view, under the default separators. */
    private fun keysOf(text: String): List<String> {
        val code = LaravelSource.codeOf(text)
        assertEquals(text.length, code.length, "the code keeps the file's offsets")
        val tree = TreeSitterCodeNode.parseTsx(code)
        assertFalse(tree.hasError, "the code parses: $code")
        return findKeys(tree, laravelExtractors(":", ".")).map { (node, key) ->
            "${text.substring(0, node.span.start).count { it == '\n' } + 1}: ${key.keyElements.joinToString("") { it.text }}${if (key.unsplit) " (unsplit)" else ""}"
        }
    }

    private fun example(path: String) = root.resolve("examples/laravel/$path").readText()

    /** `line: key` for each place a fragment is written in the example file. */
    private fun lines(path: String, vararg fragments: Pair<String, String>): List<String> {
        val text = example(path)
        return fragments.map { (fragment, key) ->
            val offset = text.indexOf(fragment).also { check(it >= 0) { "'$fragment' is not in $path" } }
            "${text.substring(0, offset).count { it == '\n' } + 1}: $key"
        }
    }

    @Test
    fun `helpers, translator and directives read, a file's key under its namespace, a phrase whole`() {
        val php = """
            <?php
            echo __('messages.welcome') . \__("Log in");
            echo trans('messages.auth.failed', ['x' => __('nested.key')]);
            echo trans_choice('messages.cart.items', 2) . Lang::choice('a.b', 1) . \Illuminate\Support\Facades\Lang::get('c.d');
            echo app('translator')->get('e.f') . app("translator")->choice('g.h', 3);
            echo __('Saved.') . __('Hello, :name');
            @lang('i.j') @choice('k.l', 2) @@lang('escaped.directive')
        """.trimIndent()

        assertEquals(
            listOf(
                "2: messages:welcome", "2: Log in", "3: messages:auth.failed", "3: nested:key", "4: messages:cart.items", "4: a:b", "4: c:d",
                "5: e:f", "5: g:h", "6: Saved. (unsplit)", "6: Hello, :name", "7: i:j", "7: k:l",
            ),
            keysOf(php),
        )
    }

    @Test
    fun `no key from other objects, other functions, runtime keys, package keys and comments`() {
        val php = """
            <?php
            ${'$'}request->get('page'); ${'$'}translator->trans('symfony.key'); ${'$'}this->__('method.key'); my__('other.key');
            __("messages.${'$'}name"); __('courier::messages.sent');
            // __('commented.line')
            # __('hashed.line')
            /* __('commented.block') */
            {{-- @lang('blade.comment') --}}
            @include('partials.nav')
        """.trimIndent()

        assertEquals(emptyList<String>(), keysOf(php))
    }

    @Test
    fun `example - controller calls, the request parameter and the package keys left out`() {
        assertEquals(
            lines(
                "app/Http/Controllers/HomeController.php",
                "'messages.welcome'" to "messages:welcome", "'messages.greeting'" to "messages:greeting", "'messages.auth.failed'" to "messages:auth.failed",
                "'messages.auth.throttle'" to "messages:auth.throttle", "'messages.only_in_english'" to "messages:only_in_english",
                "'Thanks for your order'" to "Thanks for your order", "'Saved.'" to "Saved. (unsplit)", "'Hello, :name'" to "Hello, :name",
                "'messages.does_not_exist'" to "messages:does_not_exist", "trans_choice('messages.cart.items'" to "messages:cart.items",
                "->choice('messages.cart.items'" to "messages:cart.items",
            ),
            keysOf(example("app/Http/Controllers/HomeController.php")),
        )
    }

    @Test
    fun `example - Blade echoes and directives, the included view no key`() {
        assertEquals(
            lines(
                "resources/views/welcome.blade.php",
                "<title>{{ __('messages.welcome')" to "messages:welcome", "<h1>{{ __('messages.welcome')" to "messages:welcome",
                "'messages.greeting'" to "messages:greeting", "'messages.auth.failed'" to "messages:auth.failed",
                "'messages.auth.throttle'" to "messages:auth.throttle", "'Free shipping'" to "Free shipping",
                "'messages.only_in_english'" to "messages:only_in_english", "'messages.not_in_any_file'" to "messages:not_in_any_file",
            ),
            keysOf(example("resources/views/welcome.blade.php")),
        )
        assertEquals(
            lines(
                "resources/views/cart.blade.php",
                "'Log in'" to "Log in", "{{ trans_choice('messages.cart.items'" to "messages:cart.items",
                "@choice('messages.cart.items'" to "messages:cart.items", "'messages.cart.lines'" to "messages:cart.lines",
            ),
            keysOf(example("resources/views/cart.blade.php")),
        )
    }
}
