package app.morphe.patches.aliexpress.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.aliexpress.utils.Constants.COMPATIBILITY_ALIEXPRESS
import app.morphe.util.setExtensionIsPatchIncluded

internal object AdcInitFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/app/Application;"),
)

val removeAdsPatch = bytecodePatch(
    name = "Remove ads",
    description = "Removes sponsored products and advertisements from the AliExpress home feed and search results.",
    default = true
) {
    dependsOn(app.morphe.patches.aliexpress.security.bypassSignatureCheckPatch)
    compatibleWith(COMPATIBILITY_ALIEXPRESS)
    extendWith("extensions/aliexpress.mpe")


    execute {
        val targetMethod = AdcInitFingerprint.methodOrNull ?: throw IllegalStateException(
            "Failed to resolve target method for AdcInitFingerprint. Ensure the fingerprint matches the current application version."
        )
        targetMethod.apply {
            addInstructions(
                0,
                """
                invoke-static {}, Lapp/morphe/extension/aliexpress/patches/RemoveAdsPatch;->shouldShowAd()Z
                move-result v0
                if-nez v0, :cond_skip
                invoke-static {p0}, Lapp/morphe/extension/aliexpress/patches/RemoveAdsPatch;->filterAds(Ljava/util/List;)Ljava/util/List;
                move-result-object p0
                :cond_skip
                """.trimIndent(),
            )
        }

        setExtensionIsPatchIncluded("Lapp/morphe/extension/aliexpress/patches/RemoveAdsPatch;")
    }
}
