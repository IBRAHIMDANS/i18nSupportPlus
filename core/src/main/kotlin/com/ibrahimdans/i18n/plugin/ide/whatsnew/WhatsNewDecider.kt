package com.ibrahimdans.i18n.plugin.ide.whatsnew

/**
 * Decides whether the "what's new" notification is due, given the last plugin version the user
 * was told about and the version now running.
 *
 * Kept free of the platform, and out of [WhatsNewStartupActivity], so it can be tested headlessly.
 */
object WhatsNewDecider {

    enum class Decision {
        /** Nothing to say: the running version was already announced. */
        NONE,

        /**
         * Nothing was ever recorded: a fresh install, which has no "before" to compare with.
         * The version is recorded silently. An update from a release older than this feature
         * lands here too — it cannot be told apart from an install.
         */
        RECORD_ONLY,

        /** The version changed since the last opening: announce it, then record it. */
        NOTIFY,
    }

    /**
     * [muted] is set once the user chose *Don't Show Again*: a new version is then recorded without
     * being announced, so the notification never comes back for an update that is already old.
     *
     * A patch of the minor already announced (`1.19.0` → `1.19.1`) is recorded without being
     * announced either: releases leave weekly, a patch between two trains fixes a crash or a
     * blocking issue, and a notification every few days is what makes a user mute them all
     * (RELEASING.md, Cadence). A patch of a later minor (`1.18.1` → `1.19.1`) is announced: its
     * page holds the minor's notes too.
     */
    fun decide(lastSeenVersion: String?, currentVersion: String, muted: Boolean = false): Decision = when {
        lastSeenVersion.isNullOrBlank() -> Decision.RECORD_ONLY
        lastSeenVersion == currentVersion -> Decision.NONE
        muted -> Decision.RECORD_ONLY
        minorOf(lastSeenVersion) != null && minorOf(lastSeenVersion) == minorOf(currentVersion) -> Decision.RECORD_ONLY
        else -> Decision.NOTIFY
    }

    /** The `MAJOR.MINOR` of a version, null when it does not start with two numbers. */
    private fun minorOf(version: String): Pair<Int, Int>? {
        val match = Regex("""^\s*(\d+)\.(\d+)""").find(version) ?: return null
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }
}
