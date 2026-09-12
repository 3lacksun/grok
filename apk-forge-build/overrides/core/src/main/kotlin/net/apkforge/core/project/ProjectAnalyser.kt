package net.apkforge.core.project

import net.apkforge.core.model.*
import java.nio.file.*
import javax.xml.parsers.DocumentBuilderFactory

class ProjectAnalyser {
    fun analyse(root: Path): ProjectAnalysis {
        require(Files.isDirectory(root)) { "Project root is not a directory: $root" }
        val type = detectType(root)
        val dexTrees = detectDexTrees(root)
        val total = Files.walk(root).use { it.filter(Files::isRegularFile).count() }
        val javaFiles = when (type) {
            ProjectType.GRADLE_ANDROID -> count(root.resolve("app/src/main/java"), ".java") + count(root.resolve("app/src/main/kotlin"), ".kt")
            else -> count(root.resolve("src"), ".java") + count(root.resolve("src"), ".kt")
        }
        val resources = when (type) {
            ProjectType.GRADLE_ANDROID -> count(root.resolve("app/src/main/res"), null)
            else -> count(root.resolve("res"), null)
        }
        val abis = detectAbis(root, type)
        val manifest = locateManifest(root, type)
        val app = parseApplication(root, manifest, type)
        val warnings = buildList {
            if (app == null) add("Android application metadata could not be resolved")
            if (type == ProjectType.APKTOOL_SMALI && dexTrees.isEmpty()) add("Apktool/Smali project has no smali trees")
            if (type == ProjectType.GRADLE_ANDROID && manifest == null) add("Gradle Android project has no app/src/main/AndroidManifest.xml")
            if (total > 50_000) add("Large project: $total files")
        }
        val capability = when(type) {
            ProjectType.APK, ProjectType.APKTOOL_SMALI, ProjectType.JAVA_ANDROID -> BuildCapability.LOCAL_REQUIRES_TOOLS
            ProjectType.GRADLE_ANDROID -> BuildCapability.REMOTE_RECOMMENDED
            else -> BuildCapability.UNSUPPORTED
        }
        return ProjectAnalysis(root, type, app, dexTrees, javaFiles, resources, abis, total, capability, warnings)
    }

    fun detectType(root: Path): ProjectType = when {
        Files.isRegularFile(root.resolve("input.apk")) -> ProjectType.APK
        Files.exists(root.resolve("apkforge.project.json")) -> try { ProjectCodec.decode(Files.readString(root.resolve("apkforge.project.json"))).projectType } catch (_: Exception) { ProjectType.UNKNOWN }
        (Files.exists(root.resolve("apktool.yml")) || Files.exists(root.resolve("apktool.json"))) && Files.exists(root.resolve("AndroidManifest.xml")) -> ProjectType.APKTOOL_SMALI
        Files.exists(root.resolve("AndroidManifest.xml")) && hasSmaliTree(root) -> ProjectType.APKTOOL_SMALI
        isGradleAndroid(root) -> ProjectType.GRADLE_ANDROID
        Files.exists(root.resolve("AndroidManifest.xml")) && Files.isDirectory(root.resolve("src")) -> ProjectType.JAVA_ANDROID
        else -> ProjectType.UNKNOWN
    }

    fun detectDexTrees(root: Path): List<DexTree> {
        if (!Files.isDirectory(root)) return emptyList()
        val dirs = Files.list(root).use { s -> s.filter(Files::isDirectory).map { it.fileName.toString() }.iterator().asSequence().toList() }
        return dirs.mapNotNull { name ->
            val index = when {
                name == "smali" -> 1
                name.matches(Regex("smali_classes\\d+")) -> name.removePrefix("smali_classes").toIntOrNull()
                else -> null
            }
            index?.let { DexTree(name, it, count(root.resolve(name), ".smali")) }
        }.sortedBy { it.dexIndex }
    }

    private fun hasSmaliTree(root: Path): Boolean = Files.list(root).use { s ->
        s.anyMatch { Files.isDirectory(it) && (it.fileName.toString() == "smali" || it.fileName.toString().matches(Regex("smali_classes\\d+"))) }
    }

    private fun isGradleAndroid(root: Path): Boolean {
        val hasSettings = Files.exists(root.resolve("settings.gradle")) || Files.exists(root.resolve("settings.gradle.kts"))
        val hasRootBuild = Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts"))
        val appBuild = Files.exists(root.resolve("app/build.gradle")) || Files.exists(root.resolve("app/build.gradle.kts"))
        val appManifest = Files.exists(root.resolve("app/src/main/AndroidManifest.xml"))
        return (hasSettings || hasRootBuild) && (appBuild || appManifest)
    }

    private fun locateManifest(root: Path, type: ProjectType): Path? {
        val candidates = if (type == ProjectType.GRADLE_ANDROID) listOf(
            root.resolve("app/src/main/AndroidManifest.xml"),
            root.resolve("src/main/AndroidManifest.xml"),
            root.resolve("AndroidManifest.xml")
        ) else listOf(root.resolve("AndroidManifest.xml"), root.resolve("src/main/AndroidManifest.xml"))
        return candidates.firstOrNull { Files.isRegularFile(it) }
    }

    private fun parseApplication(root: Path, manifest: Path?, type: ProjectType): ApplicationSpec? {
        val manifestSpec = manifest?.let(::parseManifest)
        if (type != ProjectType.GRADLE_ANDROID) return manifestSpec
        val gradle = listOf(root.resolve("app/build.gradle.kts"), root.resolve("app/build.gradle"), root.resolve("build.gradle.kts"), root.resolve("build.gradle"))
            .firstOrNull { Files.isRegularFile(it) }
        if (gradle == null) return manifestSpec
        return try {
            val text = Files.readString(gradle)
            val appId = firstString(text, "applicationId") ?: firstString(text, "namespace") ?: manifestSpec?.applicationId
            if (appId.isNullOrBlank()) return manifestSpec
            val versionName = firstString(text, "versionName") ?: manifestSpec?.versionName ?: "0"
            val versionCode = firstInt(text, "versionCode")?.toLong() ?: manifestSpec?.versionCode ?: 1L
            val minSdk = firstInt(text, "minSdk") ?: firstInt(text, "minSdkVersion") ?: manifestSpec?.minSdk ?: 21
            val targetSdk = firstInt(text, "targetSdk") ?: firstInt(text, "targetSdkVersion") ?: manifestSpec?.targetSdk ?: 36
            ApplicationSpec(appId, versionName, versionCode, minSdk, targetSdk)
        } catch (_: Exception) { manifestSpec }
    }

    private fun firstString(text: String, key: String): String? {
        val re = Regex("(?m)\\b${Regex.escape(key)}\\b\\s*(?:=\\s*)?[\\\"']([^\\\"']+)[\\\"']")
        return re.find(text)?.groupValues?.getOrNull(1)
    }

    private fun firstInt(text: String, key: String): Int? {
        val re = Regex("(?m)\\b${Regex.escape(key)}\\b\\s*(?:=\\s*)?(\\d+)")
        return re.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun detectAbis(root: Path, type: ProjectType): Set<String> {
        val lib = if (type == ProjectType.GRADLE_ANDROID) root.resolve("app/src/main/jniLibs") else root.resolve("lib")
        if (!Files.isDirectory(lib)) return emptySet()
        return Files.list(lib).use { s -> s.filter(Files::isDirectory).map { it.fileName.toString() }.iterator().asSequence().toSet() }
    }

    private fun count(path: Path, suffix: String?): Int = if (!Files.exists(path)) 0 else Files.walk(path).use { s ->
        s.filter(Files::isRegularFile).filter { suffix == null || it.fileName.toString().endsWith(suffix) }.count().toInt()
    }

    private fun parseManifest(path: Path): ApplicationSpec? {
        if (!Files.exists(path)) return null
        return try {
            val dbf = DocumentBuilderFactory.newInstance(); dbf.isNamespaceAware = true
            val doc = dbf.newDocumentBuilder().parse(path.toFile()); val root = doc.documentElement
            val android = "http://schemas.android.com/apk/res/android"
            val appId = root.getAttribute("package")
            val versionName = root.getAttributeNS(android, "versionName").ifBlank { "0" }
            val versionCode = root.getAttributeNS(android, "versionCode").toLongOrNull() ?: 1L
            val sdk = doc.getElementsByTagName("uses-sdk").item(0) as? org.w3c.dom.Element
            val min = sdk?.getAttributeNS(android, "minSdkVersion")?.toIntOrNull() ?: 21
            val target = sdk?.getAttributeNS(android, "targetSdkVersion")?.toIntOrNull() ?: 36
            if (appId.isBlank()) null else ApplicationSpec(appId, versionName, versionCode, min, target)
        } catch (_: Exception) { null }
    }
}
