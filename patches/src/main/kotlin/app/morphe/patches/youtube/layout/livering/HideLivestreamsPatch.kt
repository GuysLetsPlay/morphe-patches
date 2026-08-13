/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.livering

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.hide.general.hideLayoutComponentsPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

@Suppress("unused")
val hideLivestreamsPatch = bytecodePatch(
    name = "Hide livestreams",
    description = "Adds an option to hide current livestreams and live-avatar indicators."
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        addResourcesPatch,
        settingsPatch,
        hideLayoutComponentsPatch
    )

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_hide_livestreams", summary = true)
        )
    }
}
