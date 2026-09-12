package net.apkforge.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import javax.security.auth.x500.X500Principal

/** APK Forge-owned signing identity. The private key never leaves AndroidKeyStore. */
object DeviceApkSigner {
    private const val KEY_ALIAS = "apkforge-local-apk-signing-v1"

    fun signAndVerify(inputApk: File, outputApk: File): Boolean {
        require(inputApk.isFile && inputApk.length() > 0L) { "Unsigned APK is missing" }
        outputApk.parentFile?.mkdirs()
        outputApk.delete()

        val entry = signingEntry()
        val certs = entry.certificateChain.map { it as X509Certificate }
        val signer = ApkSigner.SignerConfig.Builder(
            "APK Forge Local",
            entry.privateKey,
            certs
        ).build()

        ApkSigner.Builder(listOf(signer))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()

        if (!outputApk.isFile || outputApk.length() == 0L) return false
        return ApkVerifier.Builder(outputApk).build().verify().isVerified
    }

    fun certificateSummary(): String {
        val cert = signingEntry().certificate as X509Certificate
        return "${cert.subjectX500Principal.name} · ${cert.serialNumber.toString(16)}"
    }

    private fun signingEntry(): KeyStore.PrivateKeyEntry {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { return it }

        val now = Date()
        val expiry = Calendar.getInstance().apply {
            time = now
            add(Calendar.YEAR, 30)
        }.time
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setKeySize(3072)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=APK Forge Local,O=APK Forge"))
                .setCertificateSerialNumber(BigInteger.valueOf(1L))
                .setCertificateNotBefore(now)
                .setCertificateNotAfter(expiry)
                .build()
        )
        generator.generateKeyPair()

        return (store.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry)
            ?: error("AndroidKeyStore did not return the APK Forge signing key")
    }
}
