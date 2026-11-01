package com.ibrahimdans.i18n.extract.rules

import com.ibrahimdans.i18n.extract.lingui.LinguiTransMsgidExtractor
import com.ibrahimdans.i18n.plugin.parser.CodeKeyExtractor
import com.ibrahimdans.i18n.plugin.rules.FrameworkPresets
import com.ibrahimdans.i18n.plugin.rules.FrameworkRules
import com.ibrahimdans.i18n.plugin.rules.RuleKeyExtractor

/** The extractor reading [rules] on a tree-sitter TSX tree: the framework's preset, nothing written for it here. */
fun ruleExtractor(rules: FrameworkRules): CodeKeyExtractor = RuleKeyExtractor(rules, TsxGrammar())

/** react-intl: `formatMessage({ id })`, `defineMessages` / `defineMessage` catalogues and `<FormattedMessage id>`, all from its preset. */
fun reactIntlExtractors(): List<CodeKeyExtractor> = listOf(ruleExtractor(FrameworkPresets.REACT_INTL))

/** Lingui: `msg`, `i18n._` from its preset, and the source-text `<Trans>` the macro catalogues under its own msgid. */
fun linguiExtractors(): List<CodeKeyExtractor> = listOf(ruleExtractor(FrameworkPresets.LINGUI), LinguiTransMsgidExtractor())
