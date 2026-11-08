package com.ibrahimdans.i18n.plugin.translate

/**
 * Waits [millis] between two attempts at a request. It asks [cancellation] while it waits, so a
 * translation cancelled then stops at once; tests replace it, so that none of them sleeps.
 */
fun interface Pause {
    fun pause(millis: Long, cancellation: Cancellation?)

    companion object {
        private const val SLICE_MS = 100L

        /** Sleeps in slices of a tenth of a second, asking [Cancellation.check] before each. */
        val SLEEP = Pause { millis, cancellation ->
            var left = millis
            while (left > 0) {
                cancellation?.check()
                val slice = minOf(left, SLICE_MS)
                try {
                    Thread.sleep(slice)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Pause
                }
                left -= slice
            }
            cancellation?.check()
        }
    }
}
