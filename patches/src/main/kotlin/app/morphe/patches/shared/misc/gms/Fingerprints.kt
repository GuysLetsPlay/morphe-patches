/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.shared.misc.gms

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object GnpRegistrationTargetFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Landroid/content/Context;->getPackageName()Ljava/lang/String;",
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        string("Failed to get android ID."),
        string("Exception reading GServices key.")
    )
)

/**
 * YouTube 21.45+ and YT Music 9.45+ read the Android ID in another class.
 */
internal object GnpRegistrationTargetSuspendFingerprint : Fingerprint(
    name = "invokeSuspend",
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Landroid/content/Context;->getPackageName()Ljava/lang/String;",
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        // Sets the package name.
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("Ljava/lang/String;", "L"),
            returnType = "V",
            location = MatchAfterWithin(3)
        ),
        // Sets the user serial number, the registration ID and the Android ID.
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("J", "L"),
            returnType = "V"
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("Ljava/lang/String;", "L"),
            returnType = "V"
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("J", "L"),
            returnType = "V"
        )
    )
)
internal object GooglePlayUtilityFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "I",
    parameters = listOf("L", "I"),
    filters = listOf(
        string("MetadataValueReader"),
        string("This should never happen."),
        string("com.google.android.gms")
    )
)

internal object ServiceCheckFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("L", "I"),
    strings = listOf("Google Play Services not available")
)

internal object GmsCoreSupportFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "getGmsCoreVendorGroupId",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf()
)

internal object OriginalPackageNameExtensionFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "getOriginalPackageName",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf()
)

internal object GooglePlayServicesAvailabilityFingerprint : Fingerprint(
    returnType = "I",
    parameters = listOf("Landroid/content/Context;", "I"),
    strings = listOf("This should never happen.", "MetadataValueReader"),
)

internal object GooglePlayServicesUtilAvailabilityFingerprint : Fingerprint(
    returnType = "I",
    parameters = listOf("Landroid/content/Context;", "I"),
    strings = listOf("GooglePlayServicesUtil", " requires Google Play services, but their signature is invalid."),
)

internal object GooglePlayServicesPackageSignaturesFingerprint : Fingerprint(
    returnType = "I",
    strings = listOf(
        " requires Google Play services, but their signature is invalid.",
        " requires Google Play Store, but its signature is invalid.",
    ),
)

internal object GetCredentialSuccessToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(string("GetCredentialSuccess(credential=")),
)

internal object GetCredentialFailureToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(string("GetCredentialFailure(type=")),
)

internal const val KOTLIN_RESULT_CLASS = "Lkotlin/Result;"

internal object ObfuscatedResultToStringFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    filters = listOf(string("Success(")),
    custom = { _, classDef -> classDef.type != KOTLIN_RESULT_CLASS },
)
