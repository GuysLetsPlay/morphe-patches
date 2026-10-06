/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.sleeptimer

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeMainActivityConstructorFingerprint
import app.morphe.patches.youtube.video.information.playerStatusHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.information.videoTimeHook
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/AutomaticSleepTimerPatch;"

@Suppress("unused")
val automaticSleepTimerPatch = bytecodePatch(
    name = "Automatic sleep timer",
    description = "Pauses playback after a period without user interaction during configured hours.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        videoInformationPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_auto_sleep_timer_enabled"),
            TextPreference("morphe_auto_sleep_timer_start"),
            TextPreference("morphe_auto_sleep_timer_end"),
            TextPreference("morphe_auto_sleep_timer_duration", inputType = InputType.NUMBER),
        )

        playerStatusHook(EXTENSION_CLASS, "playerStatusChanged")
        videoTimeHook(EXTENSION_CLASS, "videoTimeChanged")

        val activityClass = YouTubeMainActivityConstructorFingerprint.classDef
        val onUserInteraction = activityClass.methods.firstOrNull {
            it.name == "onUserInteraction" && it.parameters.isEmpty() && it.returnType == "V"
        }

        if (onUserInteraction != null) {
            onUserInteraction.addInstruction(
                0,
                "invoke-static {}, $EXTENSION_CLASS->userInteraction()V"
            )
        } else {
            val activitySuperClass = activityClass.superclass
            activityClass.methods.add(
                ImmutableMethod(
                    activityClass.type,
                    "onUserInteraction",
                    emptyList(),
                    "V",
                    AccessFlags.PUBLIC.value,
                    null,
                    null,
                    MutableMethodImplementation(1),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            invoke-static {}, $EXTENSION_CLASS->userInteraction()V
                            invoke-super { p0 }, $activitySuperClass->onUserInteraction()V
                            return-void
                        """
                    )
                }
            )
        }
    }
}
