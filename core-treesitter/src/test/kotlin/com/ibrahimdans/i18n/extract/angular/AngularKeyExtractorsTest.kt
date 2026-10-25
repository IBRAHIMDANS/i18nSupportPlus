package com.ibrahimdans.i18n.extract.angular

import com.ibrahimdans.i18n.extract.CodeExtractors
import com.ibrahimdans.i18n.extract.i18next.findKeys
import com.ibrahimdans.i18n.extract.treesitter.TreeSitterCodeNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AngularKeyExtractorsTest {

    /** `line: key` for each key of [text], a template read as `i18n-check` reads a `.html` file; the prefix joined with a `.`. */
    private fun templateKeys(text: String, preset: String? = null): List<String> {
        val code = AngularTemplate.codeOf(text)
        assertEquals(text.length, code.length, "the code keeps the template's offsets")
        return keysOf(text, code, preset)
    }

    private fun scriptKeys(text: String, preset: String? = null): List<String> = keysOf(text, text, preset)

    private fun keysOf(text: String, code: String, preset: String?): List<String> =
        findKeys(TreeSitterCodeNode.parseTsx(code), CodeExtractors.forPreset(preset)).map { (node, key) ->
            val line = text.substring(0, node.span.start).count { it == '\n' } + 1
            "$line: " + (key.keyPrefix + key.keyElements).joinToString(".") { it.text }
        }

    @Test
    fun `the pipes are read, their arguments left out`() {
        val template = """
            <h1>{{ 'a' | transloco }}</h1>
            <p [title]="'b' | translate: { name: user.name } | uppercase">{{ 'c' | translate:{ n: 1 } }}</p>
            <p>{{ ('d' | transloco) + ' ' + other || 'not.a.key' }}</p>
            <p>{{ 'not.a.key' | uppercase }}</p>
            <!-- {{ 'commented' | transloco }} -->
        """.trimIndent()

        assertEquals(listOf("1: a", "2: b", "2: c", "3: d"), templateKeys(template))
    }

    @Test
    fun `the attribute directives hold keys, HTML's translate yes or no does not`() {
        val template = """
            <p transloco="a"></p>
            <p [transloco]="'b'" class="x"></p>
            <p translate="c"></p>
            <p [translate]="'d'"></p>
            <p translate>e</p>
            <p translate> f </p>
            <p translate="no">not.a.key</p>
            <p translate>Hello {{ name }}</p>
            <img transloco="g" />
        """.trimIndent()

        assertEquals(listOf("1: a", "2: b", "3: c", "4: d", "5: e", "6: f", "9: g"), templateKeys(template))
    }

    @Test
    fun `a t under a transloco read or prefix takes it, the innermost directive deciding`() {
        val template = """
            <section *transloco="let t; read: 'admin'" (click)="go()">
              <h2>{{ t('title') }}</h2>
              <div><p>{{ t('users.count', { count: n }) }}</p></div>
              <footer *transloco="let t">{{ t('menu.home') }}</footer>
              <p>{{ t('after.inner') }}</p>
            </section>
            <ng-template transloco let-t [translocoPrefix]="'admin.users'">{{ t('count') }}</ng-template>
            <ng-container *transloco="let tr; prefix: 'shop'"><img /><br>{{ tr('cart') }}</ng-container>
            <p>{{ t('global') }}</p>
        """.trimIndent()

        assertEquals(
            listOf(
                "2: admin.title", "3: admin.users.count", "4: menu.home", "5: admin.after.inner",
                "7: admin.users.count", "8: shop.cart", "9: global",
            ),
            templateKeys(template),
        )
    }

    @Test
    fun `the services' methods are read on a qualifier naming the service, the standalone functions with Transloco imported`() {
        val script = """
            import { translate, translateSignal } from '@jsverse/transloco';
            this.transloco.selectTranslate('a');
            translocoService.translate('b');
            this.translate.instant('c');
            translate.get('d');
            translate('e');
            const f = translateSignal('f');
            this.http.get('not.a.key');
            toast.translate('not.a.key');
        """.trimIndent()

        assertEquals(listOf("2: a", "3: b", "4: c", "5: d", "6: e", "7: f"), scriptKeys(script))
        assertEquals(emptyList<String>(), scriptKeys("import { translate } from './i18n';\ntranslate('not.a.key');"))
    }

    @Test
    fun `a preset keeps its framework's calls`() {
        val template = "<p>{{ 'a' | transloco }} {{ 'b' | translate }}</p>\n<p transloco=\"c\" translate=\"d\"></p>"

        assertEquals(listOf("1: a", "2: c"), templateKeys(template, CodeExtractors.TRANSLOCO))
        assertEquals(listOf("1: b", "2: d"), templateKeys(template, CodeExtractors.NGX_TRANSLATE))
    }

    @Test
    fun `the template's text and plain attributes are no code`() {
        val template = """
            <p title="t('not.a.key')">t('not.a.key') {{ ok }}</p>
            <a *ngFor="let x of items" href="t('not.a.key')">{{ x }}</a>
            @if (cond) { <p>{{ t('in.block') }}</p> }
        """.trimIndent()

        assertEquals(listOf("3: in.block"), templateKeys(template))
    }
}
