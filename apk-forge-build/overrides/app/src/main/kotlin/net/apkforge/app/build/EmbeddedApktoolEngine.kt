package net.apkforge.app.build

import android.content.Context
import brut.androlib.ApkBuilder
import brut.androlib.ApkDecoder
import brut.androlib.Config
import brut.directory.ExtFile
import net.apkforge.app.security.DeviceApkSigner
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Real on-device APK rebuild path for APK Forge.
 *
 * Apktool runs in-process; the only external executable it needs for a full
 * resource rebuild is the ABI-matched AAPT2 binary packaged as libaapt2.so.
 * Final signing and signature verification are performed in-process with
 * Android apksig and an AndroidKeyStore-backed signing key.
 */
class EmbeddedApktoolEngine(private val context: Context) {
    data class Result(
        val apk: File,
        val decodedRoot: File,
        val sha256: String,
        val signatureVerified: Boolean
    )

    fun buildImportedApk(
        inputApk: File,
        workspaceRoot: File,
        displayName: String,
        onStage: (String) -> Unit = {}
    ): Result {
        require(inputApk.isFile) { "Imported APK is missing: ${inputApk.absolutePath}" }
        prepareRuntime()
        val decoded = workspaceRoot.resolve("decoded")
        onStage("Decoding APK to manifest, resources and Smali…")
        decode(inputApk, decoded)
        return buildDecoded(decoded, displayName, onStage)
    }

    fun buildDecoded(
        decodedRoot: File,
        displayName: String,
        onStage: (String) -> Unit = {}
    ): Result {
        require(decodedRoot.isDirectory) { "Decoded project directory is missing" }
        require(decodedRoot.resolve("AndroidManifest.xml").isFile) { "AndroidManifest.xml is missing" }
        require(decodedRoot.resolve("apktool.yml").isFile || decodedRoot.resolve("apktool.json").isFile) {
            "This local build path currently requires an Apktool-decoded project"
        }
        prepareRuntime()

        val session = context.cacheDir.resolve("apkforge-build-${System.nanoTime()}").apply { mkdirs() }
        val unsigned = session.resolve("unsigned.apk")
        val outputDir = context.filesDir.resolve("builds").apply { mkdirs() }
        val safe = displayName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]+"), "_").take(72).ifBlank { "project" }
        val stamp = SimpleDateFormat("ddMMyyyyHHmmss", Locale.UK).format(Date())
        val signed = outputDir.resolve("${safe}_APKFORGE_${stamp}.apk")

        onStage("Rebuilding Smali and Android resources…")
        val aapt2 = bundledAapt2()
        val config = Config().apply {
            setForced(true)
            setFrameworkDirectory(context.filesDir.resolve("apktool-framework").apply { mkdirs() }.absolutePath)
            setAaptBinary(aapt2)
        }
        ApkBuilder(ExtFile(decodedRoot), config).build(unsigned)
        require(unsigned.isFile && unsigned.length() > 0L) { "Apktool did not produce an APK" }

        onStage("Signing APK with the device-local APK Forge key…")
        val verified = DeviceApkSigner.signAndVerify(unsigned, signed)
        require(verified) { "APK signature verification failed after signing" }
        require(signed.isFile && signed.length() > 0L) { "Signed APK is missing" }

        val digest = sha256(signed)
        onStage("Build complete · signature verified · SHA-256 ${digest.take(16)}…")
        runCatching { session.deleteRecursively() }
        return Result(signed, decodedRoot, digest, true)
    }

    private fun decode(inputApk: File, destination: File) {
        val config = Config().apply {
            setForced(true)
            setFrameworkDirectory(context.filesDir.resolve("apktool-framework").apply { mkdirs() }.absolutePath)
        }
        ApkDecoder(ExtFile(inputApk), config).decode(destination)
        require(destination.resolve("AndroidManifest.xml").isFile) { "APK decode did not produce AndroidManifest.xml" }
        require(destination.resolve("apktool.yml").isFile || destination.resolve("apktool.json").isFile) {
            "APK decode did not produce Apktool metadata"
        }
    }

    private fun prepareRuntime() {
        context.cacheDir.mkdirs()
        // Apktool uses File.createTempFile() internally. Point the JVM temp area
        // at this app's private cache rather than relying on a desktop JVM default.
        System.setProperty("java.io.tmpdir", context.cacheDir.absolutePath)
    }

    private fun bundledAapt2(): File {
        val file = File(context.applicationInfo.nativeLibraryDir, "libaapt2.so")
        require(file.isFile) {
            "No ABI-compatible AAPT2 was packaged for this device (${android.os.Build.SUPPORTED_ABIS.joinToString()})"
        }
        require(file.canExecute()) { "Packaged AAPT2 is not executable: ${file.absolutePath}" }
        return file
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
