package com.ibrahimdans.i18n.plugin.translate

/**
 * Asked between two texts whether the translation should stop: [check] throws to stop it, and
 * returns when it may go on. What it throws is the caller's — in the IDE, the progress indicator's
 * own cancellation, so a translation stops the way any background task does.
 */
fun interface Cancellation {
    fun check()
}
