package com.nuvio.app.core.build

enum class TrailerPlaybackMode {
    IN_APP,
    EXTERNAL,
}

expect object AppFeaturePolicy {
    val pluginsEnabled: Boolean
    val downloadsEnabled: Boolean
    val notificationsEnabled: Boolean
    val supportersContributorsPageEnabled: Boolean
    val donationActionsEnabled: Boolean
    val donationProgressEnabled: Boolean
    val accountDeletionEnabled: Boolean
    val personalMediaAddonCopyEnabled: Boolean
    val p2pEnabled: Boolean
    val externalPlayerSupported: Boolean
    val trailerPlaybackMode: TrailerPlaybackMode
    val heroTrailerPlaybackSupported: Boolean
    /**
     * Chrome that exists to make watching comfortable: the pause metadata card,
     * the skip-intro and next-episode prompts, the opening logo animation, hero
     * trailers, Continue Watching and desktop picture-in-picture.
     *
     * The clipper build turns all of it off. Pausing is how you inspect a frame,
     * so nothing may cover one; and anything that advances playback on its own
     * destroys a trim in progress. It stays gated rather than deleted so merges
     * from upstream keep applying to these files.
     */
    val viewingChromeEnabled: Boolean
    val inAppUpdaterEnabled: Boolean
    val imdbRatingLogoEnabled: Boolean
    val mediaPlaybackForegroundServiceEnabled: Boolean
    val downloadForegroundServiceEnabled: Boolean
    val customServerConnectionsEnabled: Boolean
}
