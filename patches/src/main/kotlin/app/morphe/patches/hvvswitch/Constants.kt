/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.patches.hvvswitch

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_HVV_SWITCH = Compatibility(
        name = "HVV Switch",
        packageName = "de.hochbahn.hvvswitch",
        // The APKPure XAPK includes a required architecture split with libsekret.so.
        apkFileType = ApkFileType.XAPK_REQUIRED,
        appIconColor = 0x0066A4,
        targets = listOf(AppTarget(version = "2.5.0")),
    )
}
