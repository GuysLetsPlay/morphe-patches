package app.morphe.patches.freeadblocker.mpvrx

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

private const val SCRIPT_ASSET = "assets/in_video_extension_web_txt"
private const val HOSTS_ASSET = "assets/in_video_extension_web_list"
private val desKey = "spring.s".toByteArray(Charsets.US_ASCII)

private val providerHosts = """
    aniworld.to
    voe.sx
    do7go.com
    doodstream.com
    bysezejataos.com
    vidmoly.biz
    vidmoly.net
    vidmoly.me
    vidmoly.to
    vmpx.online
    filemoon.sx
""".trimIndent().lines()

@Suppress("unused")
val mpvRxStreamPickerPatch = rawResourcePatch(
    name = "Open HLS streams in mpvRx",
    description = "Adds an in-page stream picker for AniWorld and supported video hosts. It shows " +
        "quality and bitrate when the provider exposes them, then opens the selected HLS stream in " +
        "mpvRx.",
) {
    compatibleWith(
        Compatibility(
            name = "Free Adblocker Browser",
            packageName = "com.hsv.freeadblockerbrowser",
            appIconColor = 0x512DA8,
            targets = listOf(AppTarget("139.1.4146", 2016124146)),
        ),
    )

    execute {
        val scriptFile = get(SCRIPT_ASSET)
        val hostsFile = get(HOSTS_ASSET)
        val files = listOf(scriptFile, hostsFile)
        val missing = files.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw PatchException(
                "Browser script assets are missing: ${missing.joinToString { it.path }}. " +
                    "Apply this patch to a merged universal APK for Free Adblocker Browser 139.1.4146.",
            )
        }

        val originalScript = decryptAsset(scriptFile.readBytes())
        val helperScript = object {}.javaClass.classLoader
            .getResourceAsStream("freeadblocker/stream-helper.js")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: throw PatchException("Bundled mpvRx stream helper script is missing from this patch.")

        if (originalScript.countOccurrences("window.videoPlayerIsPremium = false;") != 1) {
            throw PatchException("The browser's built-in video script no longer matches version 139.1.4146.")
        }
        if (originalScript.contains("function startOptiSubFrameWebPage")) {
            throw PatchException("The mpvRx stream picker appears to be already installed in this APK.")
        }

        scriptFile.writeBytes(
            encryptAsset(
                originalScript + "\n;\n" + helperScript + "\n" +
                    "startOptiSubFrameWebPage(location.href);\n",
            ),
        )

        val existingHosts = decryptAsset(hostsFile.readBytes()).lineSequence().map(String::trim)
        hostsFile.writeBytes(
            encryptAsset(
                (existingHosts + providerHosts).filter(String::isNotEmpty).distinct().joinToString("\n") + "\n",
            ),
        )
    }
}

private fun decryptAsset(encrypted: ByteArray): String {
    try {
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(desKey, "DES"))
        val compressed = cipher.doFinal(encrypted)
        InflaterInputStream(ByteArrayInputStream(compressed), Inflater(true)).use { stream ->
            return stream.readBytes().toString(Charsets.UTF_8)
        }
    } catch (exception: Exception) {
        throw PatchException("Could not decode the browser's encrypted script assets: ${exception.message}")
    }
}

private fun encryptAsset(text: String): ByteArray {
    try {
        val compressed = ByteArrayOutputStream().use { output ->
            DeflaterOutputStream(output, Deflater(Deflater.BEST_COMPRESSION, true)).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
            }
            output.toByteArray()
        }
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(desKey, "DES"))
        return cipher.doFinal(compressed)
    } catch (exception: Exception) {
        throw PatchException("Could not encode the browser's encrypted script assets: ${exception.message}")
    }
}

private fun String.countOccurrences(value: String): Int = windowed(value.length).count { it == value }
