package app.morphe.patches.aliexpress.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.aliexpress.utils.Constants.COMPATIBILITY_ALIEXPRESS
import app.morphe.util.setExtensionIsPatchIncluded

internal object AdcReportFingerprint : Fingerprint(
    strings = listOf("url", "message", "invalid url"),
    returnType = "V",
)

val hideSponsoredItemsPatch = bytecodePatch(
    name = "Remove sponsored items from search",
    description = "Filters out sponsored and promoted products from search results.",
    default = true
) {
    dependsOn(app.morphe.patches.aliexpress.security.bypassSignatureCheckPatch)
    compatibleWith(COMPATIBILITY_ALIEXPRESS)
    extendWith("extensions/aliexpress.mpe")


    execute {
        AdcReportFingerprint.method.apply {
            addInstructions(
                0,
                """
                invoke-static {p0}, Lapp/morphe/extension/aliexpress/patches/RemoveSponsoredItemsPatch;->filterSearchResults(Ljava/util/List;)Ljava/util/List;
                move-result-object p0
                """.trimIndent(),
            )
        }

        setExtensionIsPatchIncluded("Lapp/morphe/extension/aliexpress/patches/RemoveSponsoredItemsPatch;")
    }
}
