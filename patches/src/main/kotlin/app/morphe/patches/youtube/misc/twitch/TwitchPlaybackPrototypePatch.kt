/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.twitch

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.IntentPreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.newIntent
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val TWITCH_INTENT = "morphe_twitch_intent"

val twitchPlaybackPrototypePatch = bytecodePatch(
    name = "Twitch playback prototype",
    description = "Adds an experimental in-app Twitch Following page for validating live and VOD playback.",
) {
    dependsOn(settingsPatch)
    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.MISC.addPreferences(
            IntentPreference(
                key = "morphe_twitch",
                intent = newIntent(TWITCH_INTENT),
            )
        )
    }
}
