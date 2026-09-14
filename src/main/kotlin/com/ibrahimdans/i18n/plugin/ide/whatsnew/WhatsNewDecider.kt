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

    fun decide(lastSeenVersion: String?, currentVersion: String): Decision = when {
        lastSeenVersion.isNullOrBlank() -> Decision.RECORD_ONLY
        lastSeenVersion == currentVersion -> Decision.NONE
        else -> Decision.NOTIFY
    }
}
