package com.ibrahimdans.i18n.extensions.technology.transloco

import com.ibrahimdans.i18n.extensions.technology.SimpleTechnology

/**
 * Angular Transloco (`@jsverse/transloco`, formerly `@ngneat/transloco`).
 *
 * Publishes `t`, the function the structural directive hands its template
 * (`*transloco="let t"`, then `t('key')`), so a module preset set to Transloco keeps it. The service
 * calls (`translocoService.translate('key')`) and the `| transloco` pipe are recognised from their
 * own syntax by `TranslocoExtractor` and `TranslocoPipeExtractor`.
 */
class TranslocoTechnology : SimpleTechnology() {
    override fun frameworkId(): String = "transloco"

    override fun translationFunctionNames(): List<String> = listOf("t")
}
