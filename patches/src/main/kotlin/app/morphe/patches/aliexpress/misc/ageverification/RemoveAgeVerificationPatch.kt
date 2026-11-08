/*
 * Copyright (C) 2026 Lorenz
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.aliexpress.misc.ageverification

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.matchSingle
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.aliexpress.misc.signin.spoofSignaturePatch
import app.morphe.patches.shared.compat.AppCompatibilities

private const val SAFETY_FILTER_CALLBACK = "Lcom/aliexpress/module/settings/service/SafetyFilterForEuCallback;"

private fun MutableMethod.completeAgeGate(callbackRegister: String) {
    addInstructions(
        0,
        """
            if-eqz $callbackRegister, :morphe_no_age_gate_callback
            const/4 v0, 0x1
            invoke-interface {$callbackRegister, v0}, $SAFETY_FILTER_CALLBACK->onFinish(Z)V
            return-void
            :morphe_no_age_gate_callback
        """.trimIndent(),
    )
}

@Suppress("unused")
val removeAgeVerificationPatch: BytecodePatch = bytecodePatch(
    name = "Remove age verification",
    description = "Skips the age and sensitive content gate so restricted product pages open without the verification mask.",
    default = true,
) {
    compatibleWith(AppCompatibilities.ALIEXPRESS)
    dependsOn(spoofSignaturePatch)

    execute {
        // Both entry points keep the caller's callback and then open a native
        // verification screen that only reports back after the user completes
        // it. Report success immediately so the mask is hidden and the product
        // loads instead. The callback parameter is the whole purpose of each
        // method, so it is safe to answer before any work is done.
        ShowSafetyFilterForEuFingerprint.matchSingle().method.completeAgeGate("p1")
        ShowUniversalSafetyFilterFingerprint.matchSingle().method.completeAgeGate("p2")
    }
}
