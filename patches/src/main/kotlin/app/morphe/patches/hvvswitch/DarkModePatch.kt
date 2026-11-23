/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.patches.hvvswitch

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourceFile
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.hvvswitch.Constants.COMPATIBILITY_HVV_SWITCH
import com.android.tools.smali.dexlib2.Opcode

private const val LIGHT_HVV_MAP_STYLE =
    "mapbox://styles/hochbahn/cm8d9wt3z00qw01sbhxx9def5"
private const val CUSTOM_HVV_MAP_STYLE =
    "mapbox://styles/hochbahn/cmbgo3l2b008p01sc1kai6ue5"
private const val DARK_MAP_STYLE = "mapbox://styles/mapbox/dark-v11"
private const val DARK_SPLASH = "#ff080b14"

private val darkSplashResourcePatch = resourcePatch(
    name = "Dark startup screen",
    description = "Uses a dark background for the HVV startup screen.",
    default = false,
) {
    execute {
        val colors = resourceFile("res/values/colors.xml")
        val contents = colors.readText()
        val colorPattern = Regex(
            """(<color\s+name="ic_splashscreen_background">)\s*#[0-9a-fA-F]{6,8}\s*(</color>)""",
        )
        val matches = colorPattern.findAll(contents).count()
        check(matches == 1) { "Expected one HVV splash background color, found $matches" }
        colors.writeText(colorPattern.replace(contents) { match ->
            "${match.groupValues[1]}$DARK_SPLASH${match.groupValues[2]}"
        })
    }
}

@Suppress("unused")
val darkModePatch = bytecodePatch(
    name = "Dark mode",
    description = "Forces the dark interface and switches every HVV map style to Mapbox Dark.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_HVV_SWITCH)
    dependsOn(darkSplashResourcePatch)

    execute {
        fun forceSavedStyle(fingerprint: Fingerprint, register: String) {
            val resultIndex = fingerprint.instructionMatches.last().index
            fingerprint.method.addInstructions(resultIndex + 1, "const-string $register, \"$DARK_MAP_STYLE\"")
        }

        // Set the value passed into the appearance flow to HVV's existing Dark enum.
        val appearanceIndex = AppearanceFlowFingerprint.instructionMatches[1].index
        AppearanceFlowFingerprint.method.addInstructions(
            appearanceIndex,
            "sget-object p1, Ljx0;->a:Ljx0;",
        )

        // The flow emits the persisted DataStore selection after its initial value;
        // replace that emitted selection too, so a previously saved Light/System value
        // cannot switch the app back to a light palette.
        val appearanceEmissionIndex = AppearanceEmissionFingerprint.instructionMatches.last().index
        AppearanceEmissionFingerprint.method.addInstructions(
            appearanceEmissionIndex,
            "sget-object v3, Ljx0;->a:Ljx0;",
        )

        fun replaceStyle(fingerprint: Fingerprint, match: Int, register: String) {
            val index = fingerprint.instructionMatches[match].index
            fingerprint.method.addInstructions(
                index + 1,
                "const-string $register, \"$DARK_MAP_STYLE\"",
            )
        }

        replaceStyle(MapStyleGn1AFingerprint, 0, "v4")

        // This renderer has two style constants in the same method. Insert from the end
        // so the first insertion does not shift the second fingerprint index.
        replaceStyle(MapStyleGn1InvokeFingerprint, 1, "v12")
        replaceStyle(MapStyleGn1InvokeFingerprint, 0, "v7")

        replaceStyle(MapStyleHepFingerprint, 0, "v14")
        replaceStyle(MapStyleJ7pFingerprint, 0, "v4")
        replaceStyle(MapStyleFhjFingerprint, 0, "v6")
        replaceStyle(MapStyleVf8Fingerprint, 0, "v4")
        replaceStyle(MapStyleIe3Fingerprint, 0, "v13")

        // Existing installs have light URLs saved in SharedPreferences. Override each
        // returned value (not only getString's fallback) so every map surface is dark.
        forceSavedStyle(MapSavedGn1AFingerprint, "v0")
        forceSavedStyle(MapSavedGn1StationaryFingerprint, "v5")
        forceSavedStyle(MapSavedGn1BikeFingerprint, "v5")
        forceSavedStyle(MapSavedHepFingerprint, "v9")
        forceSavedStyle(MapSavedFhjFingerprint, "v3")
        forceSavedStyle(MapSavedJ7pNightFingerprint, "v5")
        forceSavedStyle(MapSavedJ7pDayFingerprint, "v5")
        forceSavedStyle(MapSavedIe3Fingerprint, "v8")

        // Compose text color is argument p2. Keep bus colors untouched and restore white
        // only for RegionalExpress/Regionalbahn route labels.
        RegionalRouteTextFingerprint.method.addInstructions(
            0,
            """
                move-object/from16 v3, p0
                const-string v0, "RE"
                invoke-virtual {v3, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
                move-result v1
                if-nez v1, :darkmode_regio_white
                const-string v0, "RB"
                invoke-virtual {v3, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
                move-result v1
                if-nez v1, :darkmode_regio_white
                const-string v0, "6([0-7][0-9]|8[0-8])"
                invoke-virtual {v3, v0}, Ljava/lang/String;->matches(Ljava/lang/String;)Z
                move-result v1
                if-nez v1, :darkmode_regio_white
                goto :darkmode_regio_color_ready
                :darkmode_regio_white
                sget-wide p2, Lnn3;->e:J
                :darkmode_regio_color_ready
            """.trimIndent(),
        )
    }
}

private fun savedMapStyle(key: String, className: String, methodName: String) = Fingerprint(
    definingClass = className,
    name = methodName,
    filters = listOf(
        string(key),
        methodCall(
            definingClass = "Landroid/content/SharedPreferences;",
            name = "getString",
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
    ),
)

private val MapSavedGn1AFingerprint = savedMapStyle("mapboxStyleUri", "Lgn1;", "a")
private val MapSavedGn1StationaryFingerprint = savedMapStyle("stationaryBasedCarSharing_mapboxStyleUri", "Lgn1;", "invoke")
private val MapSavedGn1BikeFingerprint = savedMapStyle("bikeAndRide_mapboxStyleUri", "Lgn1;", "invoke")
private val MapSavedHepFingerprint = savedMapStyle("stationaryBasedCarSharing_mapboxStyleUri", "Lhep;", "b")
private val MapSavedFhjFingerprint = savedMapStyle("stationaryBasedCarSharing_mapboxStyleUri", "Lfhj;", "c")
private val MapSavedJ7pNightFingerprint = savedMapStyle("mapboxNightStyleUri", "Lj7p;", "b")
private val MapSavedJ7pDayFingerprint = savedMapStyle("mapboxStyleUri", "Lj7p;", "b")
private val MapSavedIe3Fingerprint = savedMapStyle("cityBikeConfig_mapboxStyleUri", "Lie3;", "invoke")

private val RegionalRouteTextFingerprint = Fingerprint(
    definingClass = "Lehk;",
    name = "b",
    parameters = listOf("Ljava/lang/String;", "Lzvb;", "J", "J", "Li77;", "J", "Leck;", "Lzak;", "J", "I", "Z", "I", "I", "Lgf7;", "Lgik;", "Lxx3;", "I", "I", "I"),
)

private val AppearanceEmissionFingerprint = Fingerprint(
    definingClass = "Lzw0;",
    name = "emit",
    parameters = listOf("Ljava/lang/Object;", "Lkotlin/coroutines/Continuation;"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_BOOLEAN, definingClass = "Luik;", type = "Z"),
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "Luik;", type = "Lmve;"),
        methodCall(definingClass = "Ls7c;", name = "d"),
        methodCall(definingClass = "Lu07;", name = "emit"),
    ),
)

private val AppearanceFlowFingerprint = Fingerprint(
    definingClass = "Luik;",
    name = "<init>",
    parameters = listOf("Landroid/content/Context;", "Z"),
    filters = listOf(
        string("datastore_theme_preferences"),
        methodCall(definingClass = "Lkn3;", name = "k0"),
    ),
)

private val MapStyleGn1AFingerprint = Fingerprint(
    definingClass = "Lgn1;",
    name = "a",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;"),
    filters = listOf(string(LIGHT_HVV_MAP_STYLE)),
)

private val MapStyleGn1InvokeFingerprint = Fingerprint(
    definingClass = "Lgn1;",
    name = "invoke",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;"),
    filters = listOf(
        string(LIGHT_HVV_MAP_STYLE),
        string(LIGHT_HVV_MAP_STYLE),
    ),
)

private val MapStyleHepFingerprint = Fingerprint(
    definingClass = "Lhep;",
    name = "b",
    parameters = listOf("Lef7;", "Lrqj;", "Lxx3;", "I"),
    filters = listOf(string(LIGHT_HVV_MAP_STYLE)),
)

private val MapStyleJ7pFingerprint = Fingerprint(
    definingClass = "Lj7p;",
    name = "b",
    filters = listOf(string(CUSTOM_HVV_MAP_STYLE)),
)

private val MapStyleFhjFingerprint = Fingerprint(
    definingClass = "Lfhj;",
    name = "c",
    parameters = listOf("Lrgj;", "Lef7;", "Lgf7;", "Lgf7;", "Lgf7;", "Lxx3;", "I"),
    filters = listOf(string(LIGHT_HVV_MAP_STYLE)),
)

private val MapStyleVf8Fingerprint = Fingerprint(
    definingClass = "Lvf8;",
    name = "l",
    parameters = listOf("Lqsi;", "Ljava/lang/String;", "F", "F", "I"),
    filters = listOf(string(LIGHT_HVV_MAP_STYLE)),
)

private val MapStyleIe3Fingerprint = Fingerprint(
    definingClass = "Lie3;",
    name = "invoke",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;"),
    filters = listOf(string(LIGHT_HVV_MAP_STYLE)),
)
