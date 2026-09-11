plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

val coreFixture = rootProject.layout.projectDirectory.dir("test-fixtures/apktool-smali")

tasks.register<JavaExec>("verifyCore") {
    group = "verification"
    description = "Runs the APK Forge deterministic Kotlin/JVM core regression harness."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("net.apkforge.core.CoreTests")
    args(coreFixture.asFile.absolutePath)
}

tasks.named("check") {
    dependsOn("verifyCore")
}
