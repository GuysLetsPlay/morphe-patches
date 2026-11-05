package app.morphe.patches.aliexpress.ageverification

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.aliexpress.utils.Constants.COMPATIBILITY_ALIEXPRESS

private const val SETTING_SERVICE_IMPL = "Lcom/aliexpress/module/settings/service/SettingServiceImpl;"
private const val SAFETY_FILTER_CALLBACK = "Lcom/aliexpress/module/settings/service/SafetyFilterForEuCallback;"

/**
 * The universal safety filter method is only used for the age and sensitive
 * content gate, so its log line is a stable anchor for the service class.
 */
internal object SafetyFilterServiceFingerprint : Fingerprint(
    strings = listOf("showUniversalSafetyFilter, param: "),
    returnType = "V",
)

val removeAgeVerificationPatch = bytecodePatch(
    name = "Remove age verification",
    description = "Skips the age and sensitive content gate so restricted product pages open without the verification mask.",
    default = true,
) {
    dependsOn(app.morphe.patches.aliexpress.security.bypassSignatureCheckPatch)
    compatibleWith(COMPATIBILITY_ALIEXPRESS)

    execute {
        SafetyFilterServiceFingerprint.methodOrNull ?: return@execute

        val settingService = mutableClassDefByOrNull(SETTING_SERVICE_IMPL) ?: return@execute

        // Both methods normally show a full screen gate and only report back
        // once the user has gone through it. Report success immediately so the
        // callers hide their mask and load the product instead.
        fun bypassGate(methodName: String, callbackRegister: String) {
            val method = settingService.methods.firstOrNull { it.name == methodName } ?: return
            method.addInstructions(
                0,
                """
                if-eqz $callbackRegister, :morphe_skip_gate
                const/4 v0, 0x1
                invoke-interface {$callbackRegister, v0}, $SAFETY_FILTER_CALLBACK->onFinish(Z)V
                return-void
                :morphe_skip_gate
                """.trimIndent(),
            )
        }

        bypassGate("showSafetyFilterForEu", "p1")
        bypassGate("showUniversalSafetyFilter", "p2")
    }
}
