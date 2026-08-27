/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.debug

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

@Suppress("unused")
val saveDebugLogFilePatch = bytecodePatch(
    name = "Save debug log to file",
    description = "Adds a setting in Morphe settings under Misc to save the recent log messages " +
        "to a file in the public Download folder. Useful for debugging without adb.",
) {
    dependsOn(
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.MISC.addPreferences(
            NonInteractivePreference(
                key = "morphe_save_log_to_file",
                selectable = true,
                tag = "app.morphe.extension.shared.settings.preference.SaveLogFilePreference",
            ),
        )
    }
}
