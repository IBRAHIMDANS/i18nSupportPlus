package com.ibrahimdans.i18n.plugin.translate

import com.intellij.openapi.progress.ProgressIndicator

/** This indicator as the [Cancellation] a translation checks: cancelling the task stops it between texts. */
fun ProgressIndicator.asCancellation(): Cancellation = Cancellation { checkCanceled() }
