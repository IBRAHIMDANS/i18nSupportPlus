package com.ibrahimdans.i18n.extensions.lang.js

import com.ibrahimdans.i18n.plugin.PlatformBaseTest
import com.ibrahimdans.i18n.plugin.ide.runWithConfig
import com.ibrahimdans.i18n.plugin.ide.settings.Config
import org.junit.jupiter.api.Test

/**
 * Regression tests for BUG-F, BUG-G, and BUG-H.
 *
 * BUG-F: t(variable) was annotated as "unresolved key" even though the key is fully dynamic.
 * BUG-G: t("key", { count }) was annotated as unresolved even when the key exists.
 * BUG-H: toast.t('key') was annotated as an i18n key (false positive on qualified calls).
 */
class JsFalsePositiveTest : PlatformBaseTest() {

    // ── BUG-F: fully dynamic key — t(variable) ───────────────────────────────

    @Test
    fun testTWithDynamicVariable_noAnnotation() {
        // t(k) where k is a JSReferenceExpression must not be annotated.
        // Before the fix, ReactUseTranslationHookExtractor.canExtract accepted any
        // first argument to a t() call from useTranslation(), including plain variables.
        myFixture.runWithConfig(Config(defaultNs = "translation")) {
            myFixture.configureByText(
                "TableHeader.tsx",
                """
                import { useTranslation } from 'react-i18next';
                const keys = ["table.name", "table.email"];
                export default function TableHeader() {
                    const { t } = useTranslation();
                    return keys.map((k: string) => t(k));
                }
                """.trimIndent()
            )
            myFixture.checkHighlighting(true, true, true, true)
        }
    }

    // ── BUG-H: qualified calls with non-i18n objects ──────────────────────────

    @Test
    fun testToastT_noAnnotation() {
        // toast.t('some.message') must not be annotated as an i18n key.
        // Before the fix, JSPatterns.jsArgument("t", 0) matched any method named "t".
        myFixture.runWithConfig(Config(defaultNs = "translation")) {
            myFixture.configureByText(
                "Notify.tsx",
                """
                import toast from 'react-hot-toast';
                export function notify() {
                    toast.t('some.message');
                }
                """.trimIndent()
            )
            myFixture.checkHighlighting(true, true, true, true)
        }
    }

    @Test
    fun testRouterGet_noAnnotation() {
        // router.get('/users') must not be annotated even if "get" happened to be configured.
        // Here we only test that a qualified call on a non-i18n object is ignored.
        myFixture.runWithConfig(Config(defaultNs = "translation")) {
            myFixture.configureByText(
                "Routes.ts",
                """
                import { Router } from 'express';
                const router = Router();
                router.get('/users');
                """.trimIndent()
            )
            myFixture.checkHighlighting(true, true, true, true)
        }
    }

    @Test
    fun testBareT_resolvedKey_noError() {
        // t('key') bare call with an existing key must resolve without error.
        myFixture.runWithConfig(Config(defaultNs = "translation")) {
            myFixture.addFileToProject("en/translation.json", """{"greeting":"Hello"}""")
            myFixture.configureByText(
                "App.tsx",
                """
                import { useTranslation } from 'react-i18next';
                export default function App() {
                    const { t } = useTranslation();
                    return t('greeting');
                }
                """.trimIndent()
            )
            myFixture.checkHighlighting(true, true, true, true)
        }
    }

    // ── BUG-G: t("key", { count }) with an existing key ──────────────────────

    @Test
    fun testTWithOptionsObject_resolvedKey_noError() {
        // t("actions.confirmBulkDelete", { count: 3 }) must resolve correctly
        // when the key exists. Before the fix, the options object could confuse
        // the extractor and report a false "unresolved key" annotation.
        myFixture.runWithConfig(Config(defaultNs = "translation")) {
            myFixture.addFileToProject(
                "en/translation.json",
                """{"actions":{"confirmBulkDelete":"Delete {{count}} items?"}}"""
            )
            myFixture.configureByText(
                "BulkActions.tsx",
                """
                import { useTranslation } from 'react-i18next';
                export default function BulkActions({ count }: { count: number }) {
                    const { t } = useTranslation();
                    return t('actions.confirmBulkDelete', { count });
                }
                """.trimIndent()
            )
            myFixture.checkHighlighting(true, true, true, true)
        }
    }

    @Test
    fun testTWithOptionsObjectExplicitNs_resolvedKey_noError() {
        // t("key", { count: 3 }) with an explicit options object but no ns property.
        myFixture.runWithConfig(Config(defaultNs = "translation")) {
            myFixture.addFileToProject(
                "en/translation.json",
                """{"item":{"count":"{{count}} items"}}"""
            )
            myFixture.configureByText(
                "Items.ts",
                """
                declare const t: (key: string, options?: object) => string;
                const label = t('item.count', { count: 5 });
                """.trimIndent()
            )
            myFixture.checkHighlighting(true, true, true, true)
        }
    }
}
