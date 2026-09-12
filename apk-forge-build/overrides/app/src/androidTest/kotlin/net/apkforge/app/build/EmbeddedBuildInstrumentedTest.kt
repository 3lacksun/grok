package net.apkforge.app.build

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbeddedBuildInstrumentedTest {
    @Test
    fun rebuildsAndSignsApkOnAndroidRuntime() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val fixture = target.cacheDir.resolve("apkforge-runtime-fixture.apk")
        instrumentation.context.assets.open("fixture.apk").use { input ->
            fixture.outputStream().use { output -> input.copyTo(output) }
        }
        assertTrue(fixture.isFile && fixture.length() > 0L)

        val workspace = target.cacheDir.resolve("runtime-builder-workspace").apply {
            deleteRecursively()
            mkdirs()
        }
        val result = EmbeddedApktoolEngine(target).buildImportedApk(
            inputApk = fixture,
            workspaceRoot = workspace,
            displayName = "runtime-fixture.apk"
        )

        assertTrue(result.signatureVerified)
        assertTrue(result.apk.isFile)
        assertTrue(result.apk.length() > 0L)
        assertTrue(result.sha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(result.decodedRoot.resolve("AndroidManifest.xml").isFile)
    }
}
