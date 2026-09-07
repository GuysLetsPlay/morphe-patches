/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.sleeptimer

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.layout.miniplayer.NextGenWatchLayoutOnInterceptTouchEventFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.information.videoTimeHook
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/playback/sleeptimer/AlwaysOnSleepTimerPatch;"

@Suppress("unused")
val alwaysOnSleepTimerPatch = bytecodePatch(
    name = "Always-on sleep timer",
    description = "Automatically stops video playback after a configurable amount of idle time. " +
        "Every tap on the screen restarts the timer, so playback only stops when you " +
        "stop interacting (e.g. fell asleep), preventing videos from playing all night.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        videoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        setExtensionIsPatchIncluded(EXTENSION_CLASS)

        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_always_on_sleep_timer", summary = true),
            TextPreference(
                "morphe_always_on_sleep_timer_duration",
                inputType = InputType.NUMBER,
            ),
        )

        // Hook called when a new video starts playing (player controller created).
        onCreateHook(EXTENSION_CLASS, "newVideoStarted")

        // Hook called approximately once per second with the current playback time.
        videoTimeHook(EXTENSION_CLASS, "videoTimeChanged")

        // Hook all touches on the watch layout, to restart the timer on any screen tap.
        NextGenWatchLayoutOnInterceptTouchEventFingerprint.method.addInstruction(
            0,
            "invoke-static { p1 }, $EXTENSION_CLASS->userTouchedScreen(Landroid/view/MotionEvent;)V",
        )
    }
}
