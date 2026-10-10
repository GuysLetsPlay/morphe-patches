/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.flyout

import app.morphe.patcher.Fingerprint

/**
 * Matches YouTube's playback sequencer wrapper, which owns the live queue used by the player.
 * The wrapper has a stable role in the player graph, though its obfuscated type changes between
 * YouTube releases.
 */
internal object PlaybackQueueControllerConstructorFingerprint : Fingerprint(
    classFingerprint = Fingerprint(
        custom = { _, classDef ->
            classDef.interfaces.count() == 4 &&
                    classDef.fields.count() == 15 &&
                    classDef.methods.any { it.name == "<init>" && it.parameterTypes.size == 11 }
        }
    ),
    name = "<init>",
    returnType = "V",
    parameters = List(11) { "L" },
)
